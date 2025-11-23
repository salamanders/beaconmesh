#include <furi.h>
#include <furi_hal.h>
#include <gui/gui.h>
#include <input/input.h>
#include <stdio.h>
#include <string.h>

// UUID: 183e895c-2fc8-406c-941d-4032d84c6c9a
// Little Endian for BLE
const uint8_t app_service_uuid[] = {
    0x9a, 0x6c, 0x4c, 0xd8, 0x32, 0x40, 0x1d, 0x94,
    0x6c, 0x40, 0xc8, 0x2f, 0x5c, 0x89, 0x3e, 0x18
};

#define COMMAND_MAX_LEN 20
const char* commands[] = {"HELLO", "STOP", "GO", "ALERT", "SAFE"};
const int commands_count = 5;

typedef struct {
    int command_index;
    int16_t sequence;
    bool advertising;
} AppState;

static void update_advertising(AppState* state) {
    if (!state->advertising) {
        furi_hal_bt_stop_advertising();
        return;
    }

    const char* cmd = commands[state->command_index];
    size_t cmd_len = strlen(cmd);

    // Packet Structure:
    // Flags: 3 bytes
    // Service Data (128-bit): 1 (len) + 1 (type) + 16 (uuid) + 2 (seq) + cmd_len
    // Total <= 31 bytes

    uint8_t buffer[32];
    uint8_t i = 0;

    // Flags
    buffer[i++] = 0x02;
    buffer[i++] = 0x01;
    buffer[i++] = 0x06;

    // Service Data
    uint8_t data_len = 2 + cmd_len; // Sequence + Payload
    // Element Length: Type(1) + UUID(16) + Data(data_len)
    buffer[i++] = 1 + 16 + data_len;
    buffer[i++] = 0x21; // AD Type: Service Data - 128-bit UUID

    memcpy(&buffer[i], app_service_uuid, 16);
    i += 16;

    // Sequence (Little Endian)
    buffer[i++] = (uint8_t)(state->sequence & 0xFF);
    buffer[i++] = (uint8_t)((state->sequence >> 8) & 0xFF);

    memcpy(&buffer[i], cmd, cmd_len);
    i += cmd_len;

    if (i > 31) {
        // Truncate if too long (shouldn't happen with short commands)
        i = 31;
    }

    furi_hal_bt_update_advertising_data(buffer, i);
    furi_hal_bt_start_advertising();
}

static void render_callback(Canvas* canvas, void* ctx) {
    AppState* state = (AppState*)ctx;
    canvas_clear(canvas);
    canvas_set_font(canvas, FontPrimary);
    canvas_draw_str_aligned(canvas, 64, 10, AlignCenter, AlignTop, "Beacon Remote");

    canvas_set_font(canvas, FontSecondary);
    char buffer[32];
    snprintf(buffer, sizeof(buffer), "Cmd: %s", commands[state->command_index]);
    canvas_draw_str_aligned(canvas, 64, 30, AlignCenter, AlignTop, buffer);

    snprintf(buffer, sizeof(buffer), "Seq: %d", state->sequence);
    canvas_draw_str_aligned(canvas, 64, 45, AlignCenter, AlignTop, buffer);

    canvas_draw_str_aligned(canvas, 64, 60, AlignCenter, AlignBottom, "UP/DN: Cmd | OK: Send");
}

static void input_callback(InputEvent* input_event, void* ctx) {
    FuriMessageQueue* event_queue = ctx;
    furi_message_queue_put(event_queue, input_event, FuriWaitForever);
}

int32_t beacon_remote_app(void* p) {
    UNUSED(p);
    FuriMessageQueue* event_queue = furi_message_queue_alloc(8, sizeof(InputEvent));

    AppState state = {
        .command_index = 0,
        .sequence = 0,
        .advertising = true // Advertise immediately
    };

    // Take over BT
    if(furi_hal_bt_is_active()) {
        furi_hal_bt_stop_advertising();
    }

    update_advertising(&state);

    ViewPort* view_port = view_port_alloc();
    view_port_draw_callback_set(view_port, render_callback, &state);
    view_port_input_callback_set(view_port, input_callback, event_queue);

    Gui* gui = furi_record_open(RECORD_GUI);
    gui_add_view_port(gui, view_port, GuiLayerFullscreen);

    InputEvent event;
    while(furi_message_queue_get(event_queue, &event, FuriWaitForever) == FuriStatusOk) {
        if(event.type == InputTypeShort) {
            bool update = false;
            if(event.key == InputKeyBack) {
                break;
            } else if(event.key == InputKeyUp) {
                state.command_index = (state.command_index + 1) % commands_count;
                state.sequence++;
                update = true;
            } else if(event.key == InputKeyDown) {
                state.command_index = (state.command_index - 1 + commands_count) % commands_count;
                state.sequence++;
                update = true;
            } else if(event.key == InputKeyOk) {
                 // Resend same command with new sequence
                 state.sequence++;
                 update = true;
            }

            if (update) {
                update_advertising(&state);
            }
            view_port_update(view_port);
        }
    }

    furi_hal_bt_stop_advertising();

    gui_remove_view_port(gui, view_port);
    view_port_free(view_port);
    furi_message_queue_free(event_queue);
    furi_record_close(RECORD_GUI);

    return 0;
}
