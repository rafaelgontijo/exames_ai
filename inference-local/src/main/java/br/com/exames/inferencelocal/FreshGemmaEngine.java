package br.com.exames.inferencelocal;

import com.google.ai.edge.litertlm.*;
import java.util.Collections;
import android.os.SystemClock;

/** Private Java boundary: LiteRT SDK types never enter the JVM domain or public adapter API. */
final class FreshGemmaEngine {
    static final class Output {
        final String text;
        final long loadMillis, generationMillis;
        final Integer prefillTokens, decodedTokens, contextTokens;
        Output(String text, long loadMillis, long generationMillis, Integer prefillTokens, Integer decodedTokens, Integer contextTokens) {
            this.text = text; this.loadMillis = loadMillis; this.generationMillis = generationMillis;
            this.prefillTokens = prefillTokens; this.decodedTokens = decodedTokens; this.contextTokens = contextTokens;
        }
    }

    static Output generate(String modelPath, String instruction, String text) throws Exception {
        return generate(modelPath, instruction, text, null, null, () -> {});
    }

    static Output generate(String modelPath, String instruction, String text, Runnable generating) throws Exception {
        return generate(modelPath, instruction, text, null, null, generating);
    }

    static Output generate(
        String modelPath,
        String instruction,
        String text,
        byte[] imageBytes,
        String imageMimeType,
        Runnable generating
    ) throws Exception {
        Engine.Companion.setNativeMinLogSeverity(LogSeverity.INFINITY);
        boolean hasImage = imageBytes != null && imageBytes.length > 0 && imageMimeType != null;
        Engine engine = new Engine(new EngineConfig(
            modelPath,
            new Backend.GPU(),
            hasImage ? new Backend.GPU() : null,
            null,
            SelectedInferenceConfiguration.CONTEXT_TOKENS,
            hasImage ? SelectedInferenceConfiguration.MAX_NUM_IMAGES : null,
            SelectedInferenceConfiguration.NATIVE_CACHE,
            null
        ));
        try {
            long start = SystemClock.elapsedRealtime();
            engine.initialize();
            long loadMillis = SystemClock.elapsedRealtime() - start;
            generating.run();
            ConversationConfig config = new ConversationConfig(
                hasImage ? null : Contents.Companion.of(instruction),
                Collections.emptyList(),
                Collections.emptyList(),
                new SamplerConfig(
                    SelectedInferenceConfiguration.TOP_K,
                    SelectedInferenceConfiguration.TOP_P,
                    SelectedInferenceConfiguration.TEMPERATURE,
                    SelectedInferenceConfiguration.SEED
                ),
                false,
                null,
                Collections.emptyMap(),
                null,
                false,
                SelectedInferenceConfiguration.OUTPUT_TOKENS,
                null,
                false
            );
            try (Conversation conversation = engine.createConversation(config)) {
                // Gemma channels come from model metadata; getContents excludes thought-channel text.
                long generationStart = SystemClock.elapsedRealtime();
                Message message;
                if (hasImage) {
                    String combinedPrompt = text == null || text.isBlank()
                        ? instruction
                        : instruction + "\n\n" + text;
                    Contents userContents = Contents.Companion.of(
                        new Content.ImageBytes(imageBytes),
                        new Content.Text(combinedPrompt)
                    );
                    message = conversation.sendMessage(
                        userContents,
                        Collections.emptyMap(),
                        null,
                        null,
                        null,
                        SelectedInferenceConfiguration.OUTPUT_TOKENS,
                        null,
                        null
                    );
                } else {
                    message = conversation.sendMessage(
                        text,
                        Collections.emptyMap(),
                        null,
                        null,
                        null,
                        SelectedInferenceConfiguration.OUTPUT_TOKENS,
                        null,
                        null
                    );
                }
                long generationMillis = SystemClock.elapsedRealtime() - generationStart;
                Integer prefill = null, decoded = null, context = null;
                try {
                    BenchmarkInfo info = conversation.getBenchmarkInfo();
                    if (info.getLastPrefillTokenCount() > 0) prefill = info.getLastPrefillTokenCount();
                    if (info.getLastDecodeTokenCount() > 0) decoded = info.getLastDecodeTokenCount();
                    if (conversation.getTokenCount() > 0) context = conversation.getTokenCount();
                } catch (RuntimeException ignored) { }
                return new Output(
                    message.getContents().toString(),
                    loadMillis,
                    generationMillis,
                    prefill,
                    decoded,
                    context
                );
            }
        } finally {
            if (engine.isInitialized()) engine.close();
        }
    }
}
