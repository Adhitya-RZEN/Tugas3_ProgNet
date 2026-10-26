package com.bytebox.ui;

import com.bytebox.download.DownloadManager;
import com.bytebox.download.DownloadSnapshot;
import com.bytebox.download.DownloadStatus;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.VBox;
import javafx.scene.layout.BorderPane;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

public final class DashboardController {
    @FXML private BorderPane appShell;
    @FXML private TextField urlInput;
    @FXML private ToggleGroup statusToggleGroup;
    @FXML private ToggleButton allFilter;
    @FXML private ToggleButton activeFilter;
    @FXML private ToggleButton pausedFilter;
    @FXML private ToggleButton queuedFilter;
    @FXML private ToggleButton completedFilter;
    @FXML private ToggleButton failedFilter;
    @FXML private VBox downloadList;
    @FXML private VBox emptyState;
    @FXML private Label emptyTitle;
    @FXML private Label emptyMessage;
    @FXML private Label activeCount;
    @FXML private Label activeTotal;
    @FXML private Label pausedCount;
    @FXML private Label queuedCount;
    @FXML private Label completedCount;
    @FXML private Label downloadsNavCount;
    @FXML private Label queueNavCount;
    @FXML private Label completedNavCount;
    @FXML private Label failedNavCount;
    @FXML private ProgressBar totalProgressBar;
    @FXML private ProgressIndicator totalProgressRing;
    @FXML private Label totalProgress;
    @FXML private Label activeSpeed;
    @FXML private Label totalEta;
    @FXML private Label connectionCount;
    @FXML private Label downloadDirectoryLabel;
    @FXML private Label footerMessage;
    @FXML private Button addButton;
    @FXML private Button clearUrlButton;
    @FXML private Button dashboardNav;
    @FXML private Button downloadsNav;
    @FXML private Button queueNav;
    @FXML private Button completedNav;
    @FXML private Button failedNav;

    private final ExecutorService startup = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "bytebox-startup");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<UUID, Card> cards = new HashMap<>();
    private List<UUID> renderedCardIds = List.of();
    private DownloadManager manager;
    private CompletableFuture<DownloadManager> startupFuture;
    private List<DownloadSnapshot> latestSnapshots = List.of();
    private String selectedView = "Dashboard";
    private boolean shuttingDown;

    @FXML
    private void initialize() {
        appShell.widthProperty().addListener((observable, oldWidth, newWidth) -> updateCompactLayout(newWidth.doubleValue()));
        statusToggleGroup.selectedToggleProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                applySelectedFilter(newValue);
            }
        });
        statusToggleGroup.selectToggle(allFilter);
        footerMessage.setText("Starting BYTEBOX…");
        addButton.setDisable(true);
        urlInput.setDisable(true);
        clearUrlButton.disableProperty().bind(urlInput.disableProperty());
        Path appData = Path.of(System.getProperty("user.home"), ".bytebox");
        Path downloads = Path.of(System.getProperty("user.home"), "Downloads", "BYTEBOX");
        startupFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return new DownloadManager(appData, downloads);
            } catch (IOException exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        }, startup).whenComplete((loadedManager, error) -> Platform.runLater(() -> {
            if (shuttingDown) {
                return;
            }
            if (error != null) {
                footerMessage.setText("BYTEBOX could not load its download history.");
                showError("Startup failed", rootMessage(error));
                return;
            }
            manager = loadedManager;
            manager.setListener(snapshots -> Platform.runLater(() -> render(snapshots)));
            downloadDirectoryLabel.setText(manager.downloadDirectory().toString());
            urlInput.setDisable(false);
            addButton.setDisable(false);
            footerMessage.setText("Ready when you are.");
        }));
    }

    private void updateCompactLayout(double width) {
        boolean compact = width < 1400;
        boolean current = appShell.getStyleClass().contains("compact-layout");
        if (compact && !current) appShell.getStyleClass().add("compact-layout");
        else if (!compact && current) appShell.getStyleClass().remove("compact-layout");
    }

    @FXML
    private void clearUrl() {
        urlInput.clear();
        urlInput.requestFocus();
    }

    @FXML
    private void addDownload() {
        if (manager == null) {
            showError("BYTEBOX is starting", "Wait a moment for download history to load, then add the URL again.");
            return;
        }
        String url = urlInput.getText();
        if (url == null || url.isBlank()) {
            urlInput.requestFocus();
            showError("Add a download", "Paste an HTTP or HTTPS link into the URL field first.");
            return;
        }
        addButton.setDisable(true);
        footerMessage.setText("Adding download…");
        manager.add(url).whenComplete((id, error) -> Platform.runLater(() -> {
            addButton.setDisable(false);
            if (error != null) {
                footerMessage.setText("The URL could not be added.");
                showError("Could not add download", rootMessage(error));
            } else {
                urlInput.clear();
                footerMessage.setText("Download added to the queue.");
            }
        }));
    }

    @FXML
    private void showDashboard() {
        selectFilter(allFilter);
    }

    @FXML
    private void showDownloads() {
        selectFilter(activeFilter);
    }

    @FXML
    private void showQueue() {
        selectFilter(queuedFilter);
    }

    @FXML
    private void showCompleted() {
        selectFilter(completedFilter);
    }

    @FXML
    private void showFailed() {
        selectFilter(failedFilter);
    }

    @FXML
    private void chooseDownloadDirectory() {
        if (manager == null) {
            return;
        }
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Choose download folder");
        Path existing = manager.downloadDirectory();
        if (existing.toFile().isDirectory()) {
            chooser.setInitialDirectory(existing.toFile());
        }
        Window window = downloadDirectoryLabel.getScene().getWindow();
        java.io.File selected = chooser.showDialog(window);
        if (selected == null) {
            return;
        }
        footerMessage.setText("Saving download folder…");
        manager.setDownloadDirectory(selected.toPath()).whenComplete((directory, error) -> Platform.runLater(() -> {
            if (error != null) {
                footerMessage.setText("The folder could not be saved.");
                showError("Could not use this folder", rootMessage(error));
            } else {
                downloadDirectoryLabel.setText(directory.toString());
                footerMessage.setText("New downloads will go to this folder.");
            }
        }));
    }

    @FXML
    private void openDownloadFolder() {
        if (manager == null) {
            return;
        }
        Path folder = manager.downloadDirectory();
        CompletableFuture.runAsync(() -> {
            try {
                java.nio.file.Files.createDirectories(folder);
                if (!Desktop.isDesktopSupported()) {
                    throw new IOException("Opening folders is not available on this desktop.");
                }
                Desktop.getDesktop().open(folder.toFile());
            } catch (IOException | UnsupportedOperationException exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        }).whenComplete((ignored, error) -> Platform.runLater(() -> {
            if (error != null) {
                showError("Could not open folder", rootMessage(error));
            }
        }));
    }

    @FXML
    private void showAbout() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("About BYTEBOX");
        alert.setHeaderText("BYTEBOX Download Manager");
        alert.setContentText("A Java 21 download manager for Computer Networks.\n\nDownloads run in parallel, save resumable partial files, and keep a local history.");
        alert.initOwner(downloadDirectoryLabel.getScene().getWindow());
        alert.showAndWait();
    }

    public void shutdown() {
        if (shuttingDown) {
            return;
        }
        shuttingDown = true;
        startup.shutdown();
        DownloadManager current = manager;
        if (current != null) {
            CompletableFuture.runAsync(current::close).whenComplete((ignored, error) -> Platform.runLater(Platform::exit));
        } else if (startupFuture != null) {
            startupFuture.whenComplete((loadedManager, error) -> {
                if (loadedManager == null) {
                    Platform.runLater(Platform::exit);
                } else {
                    CompletableFuture.runAsync(loadedManager::close).whenComplete((ignored, closeError) -> Platform.runLater(Platform::exit));
                }
            });
        } else {
            Platform.runLater(Platform::exit);
        }
    }

    private void render(List<DownloadSnapshot> snapshots) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> render(snapshots));
            return;
        }
        latestSnapshots = snapshots;
        long active = snapshots.stream().filter(job -> job.status() == DownloadStatus.DOWNLOADING || job.status() == DownloadStatus.PAUSING).count();
        long paused = count(snapshots, DownloadStatus.PAUSED);
        long queued = count(snapshots, DownloadStatus.QUEUED);
        long completed = count(snapshots, DownloadStatus.COMPLETED);
        long failed = count(snapshots, DownloadStatus.FAILED);
        long all = snapshots.stream().filter(job -> job.status() != DownloadStatus.CANCELLED).count();
        activeCount.setText(Long.toString(active));
        activeTotal.setText("/ " + all + (all == 1 ? " Task" : " Tasks"));
        pausedCount.setText(Long.toString(paused));
        queuedCount.setText(Long.toString(queued));
        completedCount.setText(Long.toString(completed));
        allFilter.setText("All (" + all + ")");
        activeFilter.setText("Downloading (" + active + ")");
        pausedFilter.setText("Paused (" + paused + ")");
        queuedFilter.setText("Queued (" + queued + ")");
        completedFilter.setText("Completed (" + completed + ")");
        failedFilter.setText("Failed (" + failed + ")");
        downloadsNavCount.setText(Long.toString(all));
        queueNavCount.setText(Long.toString(queued));
        completedNavCount.setText(Long.toString(completed));
        failedNavCount.setText(Long.toString(failed));
        DownloadSummary summary = DownloadSummary.calculate(snapshots);
        totalProgressBar.setProgress(summary.indicatorProgress());
        totalProgressRing.setProgress(summary.indicatorProgress());
        totalProgress.setText(summary.percentageLabel());
        activeSpeed.setText(DownloadFormatting.speed(summary.bytesPerSecond()));
        String eta = DownloadFormatting.eta(summary.remainingBytes(), summary.bytesPerSecond());
        totalEta.setText(eta.startsWith("ETA ") ? eta.substring(4) : eta);
        int connections = manager == null ? 0 : manager.activeConnections();
        connectionCount.setText(connections + " / " + DownloadManager.DEFAULT_CONCURRENT_DOWNLOADS);
        if (manager != null) {
            downloadDirectoryLabel.setText(manager.downloadDirectory().toString());
            String storageError = manager.persistenceError();
            if (storageError != null) {
                footerMessage.setText(storageError);
            } else if (selectedView.equals("Dashboard") && !snapshots.isEmpty()) {
                footerMessage.setText(snapshots.size() + (snapshots.size() == 1 ? " download in your history." : " downloads in your history."));
            }
        }

        Predicate<DownloadSnapshot> predicate = filterPredicate();
        List<DownloadSnapshot> visible = snapshots.stream().filter(predicate).toList();
        List<Card> visibleCards = new ArrayList<>(visible.size());
        for (int index = 0; index < visible.size(); index++) {
            DownloadSnapshot job = visible.get(index);
            Card card = cards.get(job.id());
            if (card == null) {
                card = createCard(job);
                if (card == null) {
                    continue;
                }
                cards.put(job.id(), card);
            }
            card.controller().setRowNumber(index + 1);
            card.controller().setSnapshot(job);
            visibleCards.add(card);
        }
        List<UUID> visibleIds = visibleCards.stream().map(Card::id).toList();
        if (!visibleIds.equals(renderedCardIds)) {
            downloadList.getChildren().setAll(visibleCards.stream().map(Card::root).toList());
            renderedCardIds = visibleIds;
        }
        boolean isEmpty = visible.isEmpty();
        downloadList.setVisible(!isEmpty);
        downloadList.setManaged(!isEmpty);
        emptyState.setVisible(isEmpty);
        emptyState.setManaged(isEmpty);
        emptyTitle.setText(snapshots.isEmpty() ? "A fresh start" : "Nothing to show here");
        emptyMessage.setText(snapshots.isEmpty()
                ? "Paste a direct HTTP or HTTPS file link above and BYTEBOX will take it from there."
                : "Try another status filter or search for a different file name.");
    }

    private Card createCard(DownloadSnapshot snapshot) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/bytebox/ui/download-item.fxml"));
            Parent root = loader.load();
            DownloadItemController controller = loader.getController();
            controller.setActions(
                    job -> guarded(() -> manager.pause(job.id())),
                    job -> guarded(() -> manager.resume(job.id())),
                    job -> guarded(() -> manager.restartFromZero(job.id())),
                    job -> guarded(() -> manager.cancel(job.id())),
                    job -> openDownloadedFile(job.destination()));
            controller.setSnapshot(snapshot);
            return new Card(snapshot.id(), root, controller);
        } catch (IOException exception) {
            showError("Download row could not be displayed", exception.getMessage());
            return null;
        }
    }

    private void guarded(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            showError("Action could not be completed", rootMessage(exception));
        }
    }

    private void updateNavigation() {
        Map<String, Button> navigation = Map.of(
                "Dashboard", dashboardNav,
                "Downloads", downloadsNav,
                "Queue", queueNav,
                "Completed", completedNav,
                "Failed", failedNav);
        navigation.values().forEach(button -> button.getStyleClass().remove("sidebar-item-selected"));
        String navigationView = switch (selectedView) {
            case "Active", "Paused" -> "Downloads";
            default -> selectedView;
        };
        Button selected = navigation.get(navigationView);
        if (selected != null && !selected.getStyleClass().contains("sidebar-item-selected")) {
            selected.getStyleClass().add("sidebar-item-selected");
        }
    }

    private void openDownloadedFile(Path file) {
        CompletableFuture.runAsync(() -> {
            try {
                if (!Desktop.isDesktopSupported()) {
                    throw new IOException("Opening files is not available on this desktop.");
                }
                Desktop.getDesktop().open(file.toFile());
            } catch (IOException | UnsupportedOperationException exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        }).whenComplete((ignored, error) -> {
            if (error != null) {
                Platform.runLater(() -> showError("Could not open file", rootMessage(error)));
            }
        });
    }

    private Predicate<DownloadSnapshot> filterPredicate() {
        Toggle selected = statusToggleGroup.getSelectedToggle();
        Object userData = selected == null ? null : selected.getUserData();
        String filter = userData == null ? "All downloads" : userData.toString();
        if (filter == null || filter.equals("All downloads")) {
            return job -> job.status() != DownloadStatus.CANCELLED;
        }
        return switch (filter) {
            case "Active" -> job -> job.status() == DownloadStatus.DOWNLOADING || job.status() == DownloadStatus.PAUSING;
            case "Paused" -> job -> job.status() == DownloadStatus.PAUSED;
            case "Queued" -> job -> job.status() == DownloadStatus.QUEUED;
            case "Completed" -> job -> job.status() == DownloadStatus.COMPLETED;
            case "Failed" -> job -> job.status() == DownloadStatus.FAILED;
            default -> job -> true;
        };
    }

    private static long count(List<DownloadSnapshot> snapshots, DownloadStatus status) {
        return snapshots.stream().filter(job -> job.status() == status).count();
    }

    private void selectFilter(ToggleButton filter) {
        if (statusToggleGroup.getSelectedToggle() == filter) {
            applySelectedFilter(filter);
        } else {
            statusToggleGroup.selectToggle(filter);
        }
    }

    private void applySelectedFilter(Toggle filter) {
        Object userData = filter.getUserData();
        String value = userData == null ? "All downloads" : userData.toString();
        selectedView = switch (value) {
            case "Active" -> "Downloads";
            case "Queued" -> "Queue";
            default -> value.equals("All downloads") ? "Dashboard" : value;
        };
        updateNavigation();
        render(latestSnapshots);
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message == null || message.isBlank() ? "Please try again." : message);
        if (downloadDirectoryLabel.getScene() != null) {
            alert.initOwner(downloadDirectoryLabel.getScene().getWindow());
        }
        alert.showAndWait();
    }

    private static String rootMessage(Throwable throwable) {
        Throwable root = throwable;
        while ((root instanceof java.util.concurrent.CompletionException || root instanceof java.util.concurrent.ExecutionException)
                && root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    private record Card(UUID id, Parent root, DownloadItemController controller) {
    }
}
