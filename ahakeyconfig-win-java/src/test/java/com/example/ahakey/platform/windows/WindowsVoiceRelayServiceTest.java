package com.example.ahakey.platform.windows;

import com.example.ahakey.model.ModeSlot;
import com.example.ahakey.model.VoicePreset;
import com.example.ahakey.model.VoiceTriggerMode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class WindowsVoiceRelayServiceTest {
    @Test
    void repeatedKeyDownIsIgnoredUntilTheMatchingKeyUp() {
        var state = new WindowsVoiceRelayService.PressState();
        var route = route(ModeSlot.MODE0, VoiceTriggerMode.PRESS_AND_HOLD);

        assertSame(route, state.begin(0x81, route));
        assertNull(state.begin(0x81, route));
        assertSame(route, state.end(0x81));
        assertNull(state.end(0x81));
    }

    @Test
    void keyUpReturnsTheRouteCapturedAtKeyDown() {
        var state = new WindowsVoiceRelayService.PressState();
        var original = route(ModeSlot.MODE0, VoiceTriggerMode.PRESS_AND_HOLD);
        var refreshed = route(ModeSlot.MODE2, VoiceTriggerMode.TOGGLE);

        state.begin(0x81, original);
        assertNull(state.begin(0x81, refreshed));

        assertSame(original, state.end(0x81));
    }

    @Test
    void drainingHeldKeysReturnsEachCapturedRouteOnce() {
        var state = new WindowsVoiceRelayService.PressState();
        var first = route(ModeSlot.MODE0, VoiceTriggerMode.PRESS_AND_HOLD);
        var second = new WindowsVoiceRelayService.VoiceRoute(
            0x80,
            ModeSlot.MODE1,
            VoicePreset.LOCAL_MODEL,
            VoiceTriggerMode.PRESS_AND_HOLD,
            false
        );
        state.begin(0x81, first);
        state.begin(0x80, second);

        List<WindowsVoiceRelayService.VoiceRoute> drained = state.drain();
        assertEquals(2, drained.size());
        assertEquals(Set.of(first, second), Set.copyOf(drained));
        assertEquals(List.of(), state.drain());
    }

    @Test
    void nativeHoldPairsOneToggleAtPressAndOneAtRelease() {
        AtomicInteger toggles = new AtomicInteger();
        var service = new WindowsVoiceRelayService(toggles::incrementAndGet);
        var hold = route(ModeSlot.MODE0, VoiceTriggerMode.PRESS_AND_HOLD);
        var tap = route(ModeSlot.MODE0, VoiceTriggerMode.TOGGLE);

        service.dispatchPress(hold, false);
        service.dispatchRelease(hold, false);
        assertEquals(2, toggles.get());

        service.dispatchPress(tap, false);
        service.dispatchRelease(tap, false);
        assertEquals(3, toggles.get());
    }

    @Test
    void weChatHoldPairsOneShortcutTapAtPressAndOneAtRelease() {
        AtomicInteger nativeToggles = new AtomicInteger();
        List<Integer> shortcutTaps = new ArrayList<>();
        var service = new WindowsVoiceRelayService(
            nativeToggles::incrementAndGet,
            shortcutTaps::add
        );
        var hold = new WindowsVoiceRelayService.VoiceRoute(
            0x81,
            ModeSlot.MODE2,
            VoicePreset.WECHAT,
            VoiceTriggerMode.PRESS_AND_HOLD,
            false
        );
        var toggle = new WindowsVoiceRelayService.VoiceRoute(
            0x81,
            ModeSlot.MODE2,
            VoicePreset.WECHAT,
            VoiceTriggerMode.TOGGLE,
            false
        );

        service.dispatchPress(hold, false);
        service.dispatchRelease(hold, false);
        service.dispatchPress(toggle, false);
        service.dispatchRelease(toggle, false);

        assertEquals(
            List.of(
                VoicePreset.WECHAT_HID_CODE,
                VoicePreset.WECHAT_HID_CODE,
                VoicePreset.WECHAT_HID_CODE
            ),
            shortcutTaps
        );
        assertEquals(0, nativeToggles.get());
    }

    @Test
    void localToggleUsesTheCurrentRecorderState() {
        AtomicBoolean recording = new AtomicBoolean(false);
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger stops = new AtomicInteger();
        var service = new WindowsVoiceRelayService(() -> {
        });
        service.setLocalVoiceRecordingSupplier(recording::get);
        service.setOnVoiceKeyDown(() -> {
            recording.set(true);
            starts.incrementAndGet();
        });
        service.setOnVoiceKeyUp(() -> {
            recording.set(false);
            stops.incrementAndGet();
        });
        var route = new WindowsVoiceRelayService.VoiceRoute(
            0x81,
            ModeSlot.MODE2,
            VoicePreset.LOCAL_MODEL,
            VoiceTriggerMode.TOGGLE,
            false
        );

        service.dispatchPress(route, false);
        service.dispatchPress(route, false);

        assertEquals(1, starts.get());
        assertEquals(1, stops.get());
    }

    private static WindowsVoiceRelayService.VoiceRoute route(
        ModeSlot mode,
        VoiceTriggerMode triggerMode
    ) {
        return new WindowsVoiceRelayService.VoiceRoute(
            0x81,
            mode,
            VoicePreset.WINDOWS_NATIVE,
            triggerMode,
            false
        );
    }
}
