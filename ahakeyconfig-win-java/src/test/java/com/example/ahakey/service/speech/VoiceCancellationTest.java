package com.example.ahakey.service.speech;

import com.example.ahakey.service.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class VoiceCancellationTest {
    static final class FakeService extends SpeechService {
        Consumer<String> partial, complete;
        @Override public boolean startListening(Consumer<String> partial, Consumer<String> complete) {
            this.partial = partial; this.complete = complete; return true;
        }
        @Override public void cancelListening() {}
        @Override public boolean isBusy() { return false; }
    }
    @Test void disablingManagerInvalidatesCallbacksBeforeLateResultsArrive() throws Exception {
        VoiceInputManager manager = new VoiceInputManager();
        manager.initialize();
        FakeService fake = new FakeService();
        Field service = VoiceInputManager.class.getDeclaredField("speechService");
        service.setAccessible(true); service.set(manager, fake);
        AtomicInteger delivered = new AtomicInteger();
        manager.startVoiceInput(text -> delivered.incrementAndGet(), text -> delivered.incrementAndGet());
        manager.startRecording();
        manager.stopVoiceInput();
        fake.partial.accept("late preview");
        fake.complete.accept("late final");
        assertEquals(0, delivered.get());
        assertFalse(manager.isRecording());
        assertFalse(manager.isActivated());
    }
}
