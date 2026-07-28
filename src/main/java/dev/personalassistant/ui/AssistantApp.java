package dev.personalassistant.ui;

import dev.personalassistant.AppHome;
import dev.personalassistant.config.AppConfig;
import dev.personalassistant.data.Database;
import dev.personalassistant.model.ChatMessage;
import dev.personalassistant.provider.AssistantProvider;
import dev.personalassistant.provider.LocalModelProvider;
import dev.personalassistant.skill.Skill;
import dev.personalassistant.skill.SkillLoader;
import dev.personalassistant.voice.AudioRecorder;
import dev.personalassistant.voice.PiperSpeechSynthesizer;
import dev.personalassistant.voice.WhisperTranscriber;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

public final class AssistantApp extends Application {
    private Database database;
    private AssistantProvider provider;
    private List<Skill> skills;
    private final AudioRecorder recorder = new AudioRecorder();
    private WhisperTranscriber transcriber;
    private PiperSpeechSynthesizer speaker;
    private Path home;
    private final String conversationId = UUID.randomUUID().toString();
    private final TextArea transcript = new TextArea();
    private final TextField input = new TextField();
    private final Button send = new Button("Send");
    private final Button microphone = new Button("🎙 Talk");
    private final CheckBox spokenReplies = new CheckBox("Speak replies");

    @Override
    public void start(Stage stage) {
        home = AppHome.resolve();
        AppConfig config = AppConfig.load(home.resolve("config/assistant.json"));
        database = new Database(home.resolve("data/conversations.db"));
        skills = SkillLoader.load(home.resolve("skills"));
        provider = new LocalModelProvider(home);
        transcriber = new WhisperTranscriber(home);
        speaker = new PiperSpeechSynthesizer(home);

        transcript.setEditable(false);
        transcript.setWrapText(true);
        transcript.setStyle("-fx-font-family: 'Segoe UI'; -fx-font-size: 14px;");
        input.setPromptText("Message your local assistant…");
        input.setOnAction(event -> submit());
        send.setDefaultButton(true);
        send.setOnAction(event -> submit());

        microphone.setDisable(!transcriber.available());
        microphone.setOnAction(event -> toggleRecording());
        spokenReplies.setSelected(speaker.available());
        spokenReplies.setDisable(!speaker.available());

        HBox composer = new HBox(8, microphone, input, send, spokenReplies);
        HBox.setHgrow(input, javafx.scene.layout.Priority.ALWAYS);
        composer.setPadding(new Insets(10));

        Label status = new Label(provider.name() + "  •  " + skills.size()
                + " skill(s)  •  voice " + (transcriber.available() && speaker.available() ? "ready" : "unavailable")
                + "  •  cloud disabled");
        status.setPadding(new Insets(7, 10, 7, 10));

        BorderPane root = new BorderPane(transcript, status, null, composer, null);
        Scene scene = new Scene(root, 820, 600);
        stage.setTitle(config.assistantName());
        stage.setScene(scene);
        stage.setMinWidth(600);
        stage.setMinHeight(420);
        stage.show();

        append("Assistant", provider.available()
                ? "Ready. The local model will load when you send your first message."
                : "Local model files are missing. Rebuild or restore the models and native folders.");
        input.requestFocus();
    }

    private void submit() {
        String text = input.getText().trim();
        if (text.isEmpty() || !provider.available()) return;
        input.clear();
        input.setDisable(true);
        send.setDisable(true);
        database.addMessage(conversationId, ChatMessage.Role.USER, text);
        append("You", text);

        provider.reply(database.messages(conversationId), skills)
                .whenComplete((reply, failure) -> Platform.runLater(() -> {
                    String response = failure == null ? reply : "Error: " + failure.getMessage();
                    database.addMessage(conversationId, ChatMessage.Role.ASSISTANT, response);
                    append("Assistant", response);
                    if (failure == null && spokenReplies.isSelected()) {
                        speaker.speak(response).exceptionally(error -> {
                            Platform.runLater(() -> append("Voice", rootMessage(error)));
                            return null;
                        });
                    }
                    input.setDisable(false);
                    send.setDisable(false);
                    input.requestFocus();
                }));
    }

    private void toggleRecording() {
        if (!recorder.isRecording()) {
            try {
                recorder.start();
                microphone.setText("■ Stop");
                append("Voice", "Listening… click Stop when you finish speaking.");
            } catch (RuntimeException e) {
                append("Voice", rootMessage(e));
            }
            return;
        }
        microphone.setDisable(true);
        microphone.setText("Transcribing…");
        Path recording = home.resolve("data/voice-input.wav");
        recorder.stop(recording)
                .thenCompose(transcriber::transcribe)
                .whenComplete((text, failure) -> Platform.runLater(() -> {
                    microphone.setText("🎙 Talk");
                    microphone.setDisable(false);
                    if (failure != null) {
                        append("Voice", rootMessage(failure));
                    } else {
                        input.setText(text);
                        submit();
                    }
                }));
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private void append(String speaker, String text) {
        if (!transcript.getText().isEmpty()) transcript.appendText("\n\n");
        transcript.appendText(speaker + "\n" + text);
    }

    @Override
    public void stop() {
        if (provider != null) provider.close();
        if (speaker != null) speaker.close();
        if (database != null) database.close();
    }
}
