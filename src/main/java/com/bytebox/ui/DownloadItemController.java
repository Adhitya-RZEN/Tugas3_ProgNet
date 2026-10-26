package com.bytebox.ui;

import com.bytebox.download.DownloadSnapshot;
import com.bytebox.download.DownloadStatus;
import javafx.fxml.FXML;
import javafx.geometry.Side;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.StackPane;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;

import java.util.Locale;
import java.util.function.Consumer;

public final class DownloadItemController {
    @FXML private Label rowNumberLabel;
    @FXML private StackPane fileIconContainer;
    @FXML private Label fileTypeIcon;
    @FXML private Label fileNameLabel;
    @FXML private Label sourceLabel;
    @FXML private Label statusBadge;
    @FXML private ProgressBar progressBar;
    @FXML private Label sizeLabel;
    @FXML private Label progressLabel;
    @FXML private Label speedLabel;
    @FXML private Label etaLabel;
    @FXML private Label errorLabel;
    @FXML private Button pauseButton;
    @FXML private Button resumeButton;
    @FXML private Button cancelButton;
    @FXML private Button restartButton;
    @FXML private Button openButton;
    @FXML private Button moreButton;

    private Consumer<DownloadSnapshot> pauseAction = ignored -> { };
    private Consumer<DownloadSnapshot> resumeAction = ignored -> { };
    private Consumer<DownloadSnapshot> restartAction = ignored -> { };
    private Consumer<DownloadSnapshot> cancelAction = ignored -> { };
    private Consumer<DownloadSnapshot> openAction = ignored -> { };
    private DownloadSnapshot snapshot;

    @FXML
    private void initialize() {
        pauseButton.setOnAction(event -> pauseAction.accept(snapshot));
        resumeButton.setOnAction(event -> resumeAction.accept(snapshot));
        restartButton.setOnAction(event -> restartAction.accept(snapshot));
        cancelButton.setOnAction(event -> cancelAction.accept(snapshot));
        openButton.setOnAction(event -> openAction.accept(snapshot));
        moreButton.setOnAction(event -> showMoreActions());
    }

    public void setActions(Consumer<DownloadSnapshot> pauseAction,
                           Consumer<DownloadSnapshot> resumeAction,
                           Consumer<DownloadSnapshot> restartAction,
                           Consumer<DownloadSnapshot> cancelAction,
                           Consumer<DownloadSnapshot> openAction) {
        this.pauseAction = pauseAction;
        this.resumeAction = resumeAction;
        this.restartAction = restartAction;
        this.cancelAction = cancelAction;
        this.openAction = openAction;
    }

    public void setRowNumber(int rowNumber) {
        rowNumberLabel.setText(Integer.toString(rowNumber));
    }

    public void setSnapshot(DownloadSnapshot snapshot) {
        this.snapshot = snapshot;
        fileNameLabel.setText(snapshot.fileName());
        updateFileIcon(snapshot.fileName());
        sourceLabel.setText(snapshot.source().toString());
        sourceLabel.setTooltip(new javafx.scene.control.Tooltip(snapshot.source().toString()));
        statusBadge.setText("● " + statusName(snapshot.status()).toUpperCase(Locale.ROOT));
        statusBadge.getStyleClass().removeIf(style -> style.startsWith("badge-") && !style.equals("badge-base"));
        statusBadge.getStyleClass().add("badge-" + snapshot.status().name().toLowerCase(Locale.ROOT));
        progressBar.getStyleClass().removeIf(style -> style.startsWith("progress-"));
        progressBar.getStyleClass().add("progress-" + snapshot.status().name().toLowerCase(Locale.ROOT));
        progressBar.setProgress(snapshot.indeterminate() ? ProgressBar.INDETERMINATE_PROGRESS : snapshot.progress());
        if (snapshot.status() == DownloadStatus.COMPLETED) {
            sizeLabel.setText(DownloadFormatting.bytes(snapshot.transferredBytes()));
            progressLabel.setText("100% complete");
        } else if (snapshot.sizeUnknown()) {
            sizeLabel.setText(DownloadFormatting.bytes(snapshot.transferredBytes()) + " downloaded");
            progressLabel.setText("Size unknown");
        } else {
            sizeLabel.setText(DownloadFormatting.bytes(snapshot.transferredBytes()) + " of " + DownloadFormatting.bytes(snapshot.totalBytes()));
            progressLabel.setText(DownloadFormatting.percent(snapshot.progress()));
        }
        speedLabel.setText(DownloadFormatting.speed(snapshot.bytesPerSecond()));
        long remaining = snapshot.totalBytes() == null ? -1 : Math.max(0, snapshot.totalBytes() - snapshot.transferredBytes());
        etaLabel.setText(remaining < 0 ? "ETA —" : DownloadFormatting.eta(remaining, snapshot.bytesPerSecond()));
        errorLabel.setText(snapshot.errorMessage() == null ? "" : snapshot.errorMessage());
        errorLabel.setManaged(snapshot.errorMessage() != null && !snapshot.errorMessage().isBlank());
        errorLabel.setVisible(errorLabel.isManaged());
        updateActions(snapshot);
    }

    private void updateFileIcon(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String extension = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        String iconClass;
        String glyph;
        switch (extension) {
            case "pdf" -> { iconClass = "icon-pdf"; glyph = "PDF"; }
            case "zip", "rar", "7z", "jar", "gz" -> { iconClass = "icon-archive"; glyph = "ZIP"; }
            case "mp4", "mov", "mkv", "webm" -> { iconClass = "icon-video"; glyph = "▶"; }
            case "png", "jpg", "jpeg", "gif", "webp" -> { iconClass = "icon-image"; glyph = "IMG"; }
            case "iso" -> { iconClass = "icon-iso"; glyph = "ISO"; }
            case "doc", "docx", "txt", "rtf" -> { iconClass = "icon-document"; glyph = "DOC"; }
            default -> { iconClass = "icon-generic"; glyph = "FILE"; }
        }
        fileTypeIcon.setText(glyph);
        fileIconContainer.setAccessibleText(fileName + " file");
        fileIconContainer.getStyleClass().removeIf(style -> style.startsWith("icon-"));
        fileIconContainer.getStyleClass().add(iconClass);
    }

    private void updateActions(DownloadSnapshot job) {
        DownloadStatus state = job.status();
        pauseButton.setVisible(state == DownloadStatus.DOWNLOADING || state == DownloadStatus.PAUSING || state == DownloadStatus.QUEUED);
        pauseButton.setManaged(pauseButton.isVisible());
        pauseButton.setText(state == DownloadStatus.PAUSING ? "…" : "Ⅱ");
        pauseButton.setAccessibleText(state == DownloadStatus.PAUSING ? "Pausing download" : "Pause download");
        pauseButton.setDisable(state == DownloadStatus.PAUSING);
        resumeButton.setVisible(state == DownloadStatus.PAUSED || (state == DownloadStatus.FAILED && !job.restartRequired()));
        resumeButton.setManaged(resumeButton.isVisible());
        resumeButton.setText("▶");
        resumeButton.setAccessibleText(state == DownloadStatus.FAILED ? "Retry failed download" : "Resume download");
        restartButton.setVisible(state == DownloadStatus.FAILED && job.restartRequired());
        restartButton.setManaged(restartButton.isVisible());
        cancelButton.setVisible(!state.isTerminal());
        cancelButton.setManaged(cancelButton.isVisible());
        cancelButton.setDisable(state == DownloadStatus.CANCELLING);
        cancelButton.setText(state == DownloadStatus.CANCELLING ? "…" : "×");
        cancelButton.setAccessibleText(state == DownloadStatus.CANCELLING ? "Cancelling download" : "Cancel download");
        openButton.setVisible(state == DownloadStatus.COMPLETED);
        openButton.setManaged(openButton.isVisible());
    }

    private void showMoreActions() {
        if (snapshot == null) return;
        ContextMenu menu = new ContextMenu();
        MenuItem copyLink = new MenuItem("Copy download link");
        copyLink.setOnAction(event -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(snapshot.source().toString());
            Clipboard.getSystemClipboard().setContent(content);
        });
        menu.getItems().add(copyLink);
        if (snapshot.status() == DownloadStatus.DOWNLOADING || snapshot.status() == DownloadStatus.QUEUED) {
            menu.getItems().add(menuItem("Pause download", () -> pauseAction.accept(snapshot)));
        } else if (snapshot.status() == DownloadStatus.PAUSED
                || (snapshot.status() == DownloadStatus.FAILED && !snapshot.restartRequired())) {
            menu.getItems().add(menuItem(snapshot.status() == DownloadStatus.FAILED ? "Retry download" : "Resume download",
                    () -> resumeAction.accept(snapshot)));
        } else if (snapshot.status() == DownloadStatus.FAILED) {
            menu.getItems().add(menuItem("Restart from zero", () -> restartAction.accept(snapshot)));
        }
        if (snapshot.status() == DownloadStatus.COMPLETED) {
            menu.getItems().add(menuItem("Open downloaded file", () -> openAction.accept(snapshot)));
        }
        if (!snapshot.status().isTerminal()) {
            menu.getItems().add(menuItem("Cancel download", () -> cancelAction.accept(snapshot)));
        }
        menu.show(moreButton, Side.BOTTOM, 0, 0);
    }

    private static MenuItem menuItem(String text, Runnable action) {
        MenuItem item = new MenuItem(text);
        item.setOnAction(event -> action.run());
        return item;
    }

    private static String statusName(DownloadStatus status) {
        String lower = status.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
