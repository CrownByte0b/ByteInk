/* Test-only tablet-v2 server in a private Weston; never loaded into a user's compositor. */
#define _GNU_SOURCE
#include <libweston/libweston.h>
#include <wayland-server.h>
#include <xkbcommon/xkbcommon.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>
#include "tablet-server.h"
#include "cursor-shape-server.h"
#include "byteink-test-server.h"

/* What a client asked a tool's cursor to be, for byteink_test.cursors. */
struct cursor_record {
    uint32_t shapes, shape, shape_serial, surfaces, surface_serial, hidden;
    int32_t hotspot_x, hotspot_y;
    struct wl_resource *surface;
    struct wl_listener surface_destroyed;
};
struct tablet_seat {
    struct wl_list link;
    struct wl_client *client;
    struct wl_resource *seat, *tablet, *tools[2];
    struct wl_resource *surfaces[2];
    struct wl_list devices;
    int proximity[2];
    uint32_t ordinal, proximity_serial[2];
    struct cursor_record cursors[2];
};
struct device { struct wl_list link; struct wl_resource *resource; struct tablet_seat *seat; };
struct touch_binding { struct wl_list link; struct wl_resource *resource; };
struct pointer_binding { struct wl_list link; struct wl_resource *resource; };
/* A cursor-shape device of a tablet tool; a wl_pointer's device (the toolkit's own) has none. */
struct shape_device { struct wl_resource *tool; struct wl_listener tool_destroyed; };
static struct wl_list tablets, touches, pointers;
static struct wl_global *manager, *shape_manager;
static struct wl_display *display;
static uint32_t seat_ordinals;
/* The default xkb keymap as text, for the seat's keyboard; null when it could not be compiled. */
static char *keymap;
static void destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static const struct zwp_tablet_v2_interface tablet_impl = { .destroy = destroy };
static void cursor(struct wl_client *c, struct wl_resource *r, uint32_t serial, struct wl_resource *s, int32_t x, int32_t y) {}
static void cursor_surface_destroyed(struct wl_listener *listener, void *data) {
    struct cursor_record *k = wl_container_of(listener, k, surface_destroyed);
    wl_list_remove(&k->surface_destroyed.link);
    k->surface = NULL;
}
static void track_surface(struct cursor_record *k, struct wl_resource *surface) {
    if (k->surface == surface) return;
    if (k->surface) wl_list_remove(&k->surface_destroyed.link);
    k->surface = surface;
    if (surface) {
        k->surface_destroyed.notify = cursor_surface_destroyed;
        wl_resource_add_destroy_listener(surface, &k->surface_destroyed);
    }
}
static void reset_cursor(struct cursor_record *k) { track_surface(k, NULL); memset(k, 0, sizeof *k); }
static int tool_index(struct tablet_seat *s, struct wl_resource *tool) {
    for (int i = 0; i < 2; i++) if (s->tools[i] == tool) return i;
    return -1;
}
static int current_serial(struct tablet_seat *s, int i, uint32_t serial) {
    return s->proximity[i] && serial == s->proximity_serial[i];
}
static void tool_cursor(struct wl_client *c, struct wl_resource *r, uint32_t serial, struct wl_resource *surface, int32_t x, int32_t y) {
    struct device *d = wl_resource_get_user_data(r);
    int i = d ? tool_index(d->seat, r) : -1;
    if (i < 0) return;
    struct cursor_record *k = &d->seat->cursors[i];
    k->surface_serial = current_serial(d->seat, i, serial);
    if (surface) { k->surfaces++; k->hotspot_x = x; k->hotspot_y = y; track_surface(k, surface); }
    else k->hidden++;
}
static const struct zwp_tablet_tool_v2_interface tool_impl = { .set_cursor = tool_cursor, .destroy = destroy };
static const struct zwp_tablet_pad_v2_interface pad_impl = { .destroy = destroy };
static const struct zwp_tablet_pad_group_v2_interface group_impl = { .destroy = destroy };
static const struct zwp_tablet_pad_ring_v2_interface ring_impl = { .destroy = destroy };
static const struct zwp_tablet_pad_strip_v2_interface strip_impl = { .destroy = destroy };
static void device_destroyed(struct wl_resource *r) {
    struct device *device = wl_resource_get_user_data(r);
    struct tablet_seat *s = device->seat;
    if (s->tablet == r) s->tablet = NULL;
    for (int i = 0; i < 2; i++) if (s->tools[i] == r) s->tools[i] = NULL;
    wl_list_remove(&device->link); free(device);
}
static struct wl_resource *device(struct tablet_seat *s, const struct wl_interface *type, const void *implementation) {
    struct device *device = calloc(1, sizeof *device); device->seat = s;
    device->resource = wl_resource_create(s->client, type, 1, 0);
    wl_resource_set_implementation(device->resource, implementation, device, device_destroyed);
    wl_list_insert(&s->devices, &device->link); return device->resource;
}
static void add_tablet(struct tablet_seat *s) {
    s->tablet = device(s, &zwp_tablet_v2_interface, &tablet_impl);
    for (int i = 0; i < 2; i++) s->proximity[i] = 0;
    zwp_tablet_seat_v2_send_tablet_added(s->seat, s->tablet);
    zwp_tablet_v2_send_name(s->tablet, "ByteInk isolated test tablet");
    zwp_tablet_v2_send_id(s->tablet, 1, 1); zwp_tablet_v2_send_done(s->tablet);
}
static void add_tool(struct tablet_seat *s, int i) {
    s->tools[i] = device(s, &zwp_tablet_tool_v2_interface, &tool_impl);
    s->proximity[i] = 0;
    reset_cursor(&s->cursors[i]);
    zwp_tablet_seat_v2_send_tool_added(s->seat, s->tools[i]);
    zwp_tablet_tool_v2_send_type(s->tools[i], ZWP_TABLET_TOOL_V2_TYPE_PEN);
    if (i == 0) { // Second tool deliberately has no optional axes.
        zwp_tablet_tool_v2_send_capability(s->tools[i], ZWP_TABLET_TOOL_V2_CAPABILITY_PRESSURE);
        zwp_tablet_tool_v2_send_capability(s->tools[i], ZWP_TABLET_TOOL_V2_CAPABILITY_TILT);
    }
    zwp_tablet_tool_v2_send_done(s->tools[i]);
}
static void add_pad(struct tablet_seat *s) {
    struct wl_resource *pad = device(s, &zwp_tablet_pad_v2_interface, &pad_impl);
    struct wl_resource *group = device(s, &zwp_tablet_pad_group_v2_interface, &group_impl);
    struct wl_resource *ring = device(s, &zwp_tablet_pad_ring_v2_interface, &ring_impl);
    struct wl_resource *strip = device(s, &zwp_tablet_pad_strip_v2_interface, &strip_impl);
    struct wl_array buttons; wl_array_init(&buttons);
    zwp_tablet_seat_v2_send_pad_added(s->seat, pad);
    zwp_tablet_pad_v2_send_group(pad, group);
    zwp_tablet_pad_group_v2_send_buttons(group, &buttons);
    zwp_tablet_pad_group_v2_send_ring(group, ring);
    zwp_tablet_pad_group_v2_send_strip(group, strip);
    zwp_tablet_pad_group_v2_send_modes(group, 1); zwp_tablet_pad_group_v2_send_done(group);
    zwp_tablet_pad_v2_send_buttons(pad, 0); zwp_tablet_pad_v2_send_done(pad);
    zwp_tablet_pad_ring_v2_send_angle(ring, wl_fixed_from_int(45)); zwp_tablet_pad_ring_v2_send_frame(ring, 1000);
    zwp_tablet_pad_strip_v2_send_position(strip, 12345); zwp_tablet_pad_strip_v2_send_frame(strip, 1000);
    wl_array_release(&buttons);
}
static void seat_destroyed(struct wl_resource *r) {
    struct tablet_seat *s = wl_resource_get_user_data(r);
    struct device *device, *next;
    wl_list_for_each_safe(device, next, &s->devices, link) wl_resource_destroy(device->resource);
    for (int i = 0; i < 2; i++) track_surface(&s->cursors[i], NULL);
    wl_list_remove(&s->link); free(s);
}
static const struct zwp_tablet_seat_v2_interface tablet_seat_impl = { .destroy = destroy };
static void get_tablet_seat(struct wl_client *c, struct wl_resource *r, uint32_t id, struct wl_resource *seat) {
    // Only our synthetic seat participates; native headless Weston has no input devices.
    struct tablet_seat *s = calloc(1, sizeof *s); s->client = c; wl_list_init(&s->devices);
    s->ordinal = ++seat_ordinals;
    s->seat = wl_resource_create(c, &zwp_tablet_seat_v2_interface, 1, id);
    wl_resource_set_implementation(s->seat, &tablet_seat_impl, s, seat_destroyed);
    wl_list_insert(&tablets, &s->link); add_tablet(s); add_tool(s, 0); add_tool(s, 1); add_pad(s);
}
static const struct zwp_tablet_manager_v2_interface manager_impl = { .get_tablet_seat = get_tablet_seat, .destroy = destroy };
static void bind_manager(struct wl_client *c, void *data, uint32_t version, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &zwp_tablet_manager_v2_interface, 1, id);
    wl_resource_set_implementation(r, &manager_impl, NULL, NULL);
}
static void shape_tool_destroyed(struct wl_listener *listener, void *data) {
    struct shape_device *d = wl_container_of(listener, d, tool_destroyed);
    wl_list_remove(&d->tool_destroyed.link);
    d->tool = NULL;
}
static void shape_device_destroyed(struct wl_resource *r) {
    struct shape_device *d = wl_resource_get_user_data(r);
    if (!d) return;
    if (d->tool) wl_list_remove(&d->tool_destroyed.link);
    free(d);
}
static void set_shape(struct wl_client *c, struct wl_resource *r, uint32_t serial, uint32_t shape) {
    // Clients bind version 1 here, whose shapes end with zoom_out.
    if (shape < WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_DEFAULT || shape > WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_ZOOM_OUT) {
        wl_resource_post_error(r, WP_CURSOR_SHAPE_DEVICE_V1_ERROR_INVALID_SHAPE, "invalid shape %u", shape);
        return;
    }
    struct shape_device *d = wl_resource_get_user_data(r);
    if (!d || !d->tool) return;
    struct device *tool = wl_resource_get_user_data(d->tool);
    int i = tool_index(tool->seat, d->tool);
    if (i < 0) return;
    struct cursor_record *k = &tool->seat->cursors[i];
    k->shapes++; k->shape = shape; k->shape_serial = current_serial(tool->seat, i, serial);
}
static const struct wp_cursor_shape_device_v1_interface shape_device_impl = { .destroy = destroy, .set_shape = set_shape };
static void shape_device(struct wl_client *c, struct wl_resource *m, uint32_t id, struct wl_resource *tool) {
    struct wl_resource *r = wl_resource_create(c, &wp_cursor_shape_device_v1_interface, wl_resource_get_version(m), id);
    struct shape_device *d = NULL;
    if (tool) {
        d = calloc(1, sizeof *d); d->tool = tool;
        d->tool_destroyed.notify = shape_tool_destroyed;
        wl_resource_add_destroy_listener(tool, &d->tool_destroyed);
    }
    wl_resource_set_implementation(r, &shape_device_impl, d, shape_device_destroyed);
}
static void pointer_shape(struct wl_client *c, struct wl_resource *m, uint32_t id, struct wl_resource *pointer) { shape_device(c, m, id, NULL); }
static void tool_shape(struct wl_client *c, struct wl_resource *m, uint32_t id, struct wl_resource *tool) { shape_device(c, m, id, tool); }
static const struct wp_cursor_shape_manager_v1_interface shape_manager_impl = {
    .destroy = destroy, .get_pointer = pointer_shape, .get_tablet_tool_v2 = tool_shape };
static void bind_shape_manager(struct wl_client *c, void *data, uint32_t version, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &wp_cursor_shape_manager_v1_interface, 1, id);
    wl_resource_set_implementation(r, &shape_manager_impl, NULL, NULL);
}
static void touch_destroyed(struct wl_resource *r) {
    struct touch_binding *t = wl_resource_get_user_data(r); wl_list_remove(&t->link); free(t);
}
static const struct wl_touch_interface touch_impl = { .release = destroy };
static void get_touch(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    struct touch_binding *t = calloc(1, sizeof *t);
    t->resource = wl_resource_create(c, &wl_touch_interface, 5, id);
    wl_resource_set_implementation(t->resource, &touch_impl, t, touch_destroyed);
    wl_list_insert(&touches, &t->link);
}
static void pointer_destroyed(struct wl_resource *r) {
    struct pointer_binding *p = wl_resource_get_user_data(r); wl_list_remove(&p->link); free(p);
}
static const struct wl_pointer_interface pointer_impl = { .set_cursor = cursor, .release = destroy };
static void get_pointer(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    struct pointer_binding *p = calloc(1, sizeof *p);
    p->resource = wl_resource_create(c, &wl_pointer_interface, 5, id);
    wl_resource_set_implementation(p->resource, &pointer_impl, p, pointer_destroyed);
    wl_list_insert(&pointers, &p->link);
}
static const struct wl_keyboard_interface keyboard_impl = { .release = destroy };
static void get_keyboard(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    // Real seats have a keyboard, and toolkits expect its keymap: JBR's xkb state stays null without
    // one, and its caps-lock query (Compose asks on every mouse event) then crashes.
    struct wl_resource *k = wl_resource_create(c, &wl_keyboard_interface, wl_resource_get_version(r), id);
    wl_resource_set_implementation(k, &keyboard_impl, NULL, NULL);
    size_t size = strlen(keymap) + 1;
    int fd = memfd_create("byteink-test-keymap", MFD_CLOEXEC);
    if (fd < 0) return;
    if (write(fd, keymap, size) == (ssize_t) size) wl_keyboard_send_keymap(k, WL_KEYBOARD_KEYMAP_FORMAT_XKB_V1, fd, size);
    close(fd);
    if (wl_resource_get_version(k) >= WL_KEYBOARD_REPEAT_INFO_SINCE_VERSION) wl_keyboard_send_repeat_info(k, 25, 600);
}
static const struct wl_seat_interface seat_impl = {
    .get_pointer = get_pointer, .get_keyboard = get_keyboard, .get_touch = get_touch, .release = destroy };
static void bind_seat(struct wl_client *c, void *data, uint32_t version, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &wl_seat_interface, 5, id);
    wl_resource_set_implementation(r, &seat_impl, NULL, NULL);
    wl_seat_send_capabilities(r, WL_SEAT_CAPABILITY_TOUCH | WL_SEAT_CAPABILITY_POINTER | (keymap ? WL_SEAT_CAPABILITY_KEYBOARD : 0));
    wl_seat_send_name(r, "byteink-test");
}
static void send_input(struct wl_client *c, struct wl_resource *r, struct wl_resource *surface,
        uint32_t index, int32_t phase, wl_fixed_t x, wl_fixed_t y, int32_t pressure,
        wl_fixed_t tx, wl_fixed_t ty, uint32_t time) {
    uint32_t serial = wl_display_next_serial(display);
    if (phase >= 11) {
        struct pointer_binding *p;
        wl_list_for_each(p, &pointers, link) if (wl_resource_get_client(p->resource) == c) {
            if (phase == 11) wl_pointer_send_enter(p->resource, serial, surface, x, y);
            wl_pointer_send_motion(p->resource, time, x, y);
            if (phase == 11 || phase == 13) wl_pointer_send_button(p->resource, serial, time, 272, phase == 11 ? WL_POINTER_BUTTON_STATE_PRESSED : WL_POINTER_BUTTON_STATE_RELEASED);
            wl_pointer_send_frame(p->resource);
            if (phase == 13) wl_pointer_send_leave(p->resource, serial, surface);
        }
        return;
    }
    if (phase >= 7) {
        struct touch_binding *t;
        wl_list_for_each(t, &touches, link) if (wl_resource_get_client(t->resource) == c) {
            if (phase == 7) wl_touch_send_down(t->resource, serial, time, surface, index, x, y);
            if (phase == 8) wl_touch_send_motion(t->resource, time, index, x, y);
            if (phase == 9) wl_touch_send_up(t->resource, serial, time, index);
            if (phase == 10) wl_touch_send_cancel(t->resource);
            else wl_touch_send_frame(t->resource);
        }
        return;
    }
    struct tablet_seat *s;
    wl_list_for_each(s, &tablets, link) if (s->client == c) {
        int i = index % 2;
        if (!s->tablet) add_tablet(s);
        if (!s->tools[i]) add_tool(s, i);
        struct wl_resource *tool = s->tools[i];
        if (phase == 4) { zwp_tablet_tool_v2_send_removed(tool); s->tools[i] = NULL; continue; }
        if (phase == 5) { zwp_tablet_v2_send_removed(s->tablet); s->tablet = NULL; continue; }
        if (!s->proximity[i] || s->surfaces[i] != surface) {
            if (s->proximity[i]) { zwp_tablet_tool_v2_send_proximity_out(tool); zwp_tablet_tool_v2_send_frame(tool, time); }
            zwp_tablet_tool_v2_send_proximity_in(tool, serial, s->tablet, surface); s->proximity[i] = 1; s->surfaces[i] = surface;
            s->proximity_serial[i] = serial;
        }
        zwp_tablet_tool_v2_send_motion(tool, x, y);
        if (i == 0 && pressure >= 0) {
            zwp_tablet_tool_v2_send_pressure(tool, pressure); zwp_tablet_tool_v2_send_tilt(tool, tx, ty);
        }
        if (phase == 0 || phase == 6) zwp_tablet_tool_v2_send_down(tool, serial);
        if (phase == 2 || phase == 6) zwp_tablet_tool_v2_send_up(tool);
        if (phase == 3) { zwp_tablet_tool_v2_send_proximity_out(tool); s->proximity[i] = 0; }
        zwp_tablet_tool_v2_send_frame(tool, time);
    }
}
static void report_cursors(struct wl_client *c, struct wl_resource *r) {
    struct tablet_seat *s;
    wl_list_for_each(s, &tablets, link) if (s->client == c) for (int i = 0; i < 2; i++) {
        struct cursor_record *k = &s->cursors[i];
        if (!k->shapes && !k->surfaces && !k->hidden) continue;
        int32_t width = 0, height = 0, scale = 0;
        // Weston keeps its weston_surface as each wl_surface resource's user data.
        struct weston_surface *surface = k->surface ? wl_resource_get_user_data(k->surface) : NULL;
        if (surface) {
            width = surface->width_from_buffer; height = surface->height_from_buffer;
            scale = surface->buffer_viewport.buffer.scale;
        }
        byteink_test_send_cursor(r, s->ordinal, i, k->shapes, k->shape, k->shape_serial, k->surfaces,
            k->surface_serial, k->hidden, k->hotspot_x, k->hotspot_y, width, height, scale);
    }
}
static const struct byteink_test_interface control_impl = { .send = send_input, .destroy = destroy, .cursors = report_cursors };
static void bind_control(struct wl_client *c, void *data, uint32_t version, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &byteink_test_interface, version, id);
    wl_resource_set_implementation(r, &control_impl, NULL, NULL);
}
static bool filter(const struct wl_client *c, const struct wl_global *global, void *data) {
    // Some Weston versions already expose tablet-v2 and cursor-shape-v1; publish exactly ours.
    const char *name = wl_global_get_interface(global)->name;
    if (!strcmp(name, "zwp_tablet_manager_v2")) return global == manager;
    if (!strcmp(name, "wp_cursor_shape_manager_v1")) return global == shape_manager;
    // These Weston extensions require Weston's own seat/pointer userdata. Our synthetic resources
    // intentionally provide only core input; don't let toolkit/GTK clients pass them to extensions.
    return strcmp(name, "zwp_relative_pointer_manager_v1") && strcmp(name, "zwp_pointer_constraints_v1") &&
        strcmp(name, "zwp_pointer_gestures_v1") &&
        strcmp(name, "zwp_keyboard_shortcuts_inhibit_manager_v1") && strcmp(name, "xdg_activation_v1");
}
WL_EXPORT int wet_module_init(struct weston_compositor *compositor, int *argc, char *argv[]) {
    display = compositor->wl_display; wl_list_init(&tablets); wl_list_init(&touches); wl_list_init(&pointers);
    struct xkb_context *context = xkb_context_new(XKB_CONTEXT_NO_FLAGS);
    struct xkb_keymap *compiled = context ? xkb_keymap_new_from_names(context, NULL, XKB_KEYMAP_COMPILE_NO_FLAGS) : NULL;
    if (compiled) keymap = xkb_keymap_get_as_string(compiled, XKB_KEYMAP_FORMAT_TEXT_V1);
    xkb_keymap_unref(compiled); xkb_context_unref(context);
    manager = wl_global_create(display, &zwp_tablet_manager_v2_interface, 1, NULL, bind_manager);
    shape_manager = wl_global_create(display, &wp_cursor_shape_manager_v1_interface, 1, NULL, bind_shape_manager);
    wl_display_set_global_filter(display, filter, NULL);
    wl_global_create(display, &wl_seat_interface, 5, NULL, bind_seat);
    wl_global_create(display, &byteink_test_interface, 2, NULL, bind_control);
    return 0;
}
