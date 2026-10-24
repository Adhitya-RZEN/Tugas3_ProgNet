package com.bytebox;

import com.bytebox.ui.DashboardController;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

public final class ByteboxApplication extends Application {
    private DashboardController controller;

    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader loader = new FXMLLoader(ByteboxApplication.class.getResource("/com/bytebox/ui/main-view.fxml"));
        Parent root = loader.load();
        controller = loader.getController();
        Scene scene = new Scene(root, 1380, 900);
        scene.getStylesheets().add(ByteboxApplication.class.getResource("/com/bytebox/ui/app.css").toExternalForm());
        stage.setTitle("BYTEBOX — Download Manager");
        stage.setMinWidth(1280);
        stage.setMinHeight(720);
        stage.setScene(scene);
        stage.setOnCloseRequest(event -> {
            event.consume();
            controller.shutdown();
        });
        stage.setMaximized(true);
        stage.show();
    }

    @Override
    public void stop() {
        if (controller != null) {
            controller.shutdown();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
