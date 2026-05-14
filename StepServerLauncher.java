package com.eratverbum.stepbible.bootstrap;

import com.tyndalehouse.step.server.STEPTomcatServer;
import java.lang.reflect.Method;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;

public class StepServerLauncher {
    public static void main(String[] args) {
        try {
            // Ensure temp directory exists
            String tmpdir = System.getProperty("java.io.tmpdir");
            if (tmpdir != null) {
                Files.createDirectories(Paths.get(tmpdir));
            }

            STEPTomcatServer server = new STEPTomcatServer(true);
            Method start = STEPTomcatServer.class.getDeclaredMethod("start");
            start.setAccessible(true);
            start.invoke(server);
        } catch (Exception e) {
            try {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                pw.println("=== STEP Server Error ===");
                if (e.getCause() != null) {
                    pw.println("Exception: " + e.getCause().getMessage());
                    e.getCause().printStackTrace(pw);
                } else {
                    pw.println("Exception: " + e.getMessage());
                    e.printStackTrace(pw);
                }
                pw.flush();
                java.io.FileOutputStream fos = new java.io.FileOutputStream(
                    "/data/data/com.eratverbum.stepbible/files/step_crash.log");
                fos.write(sw.toString().getBytes("UTF-8"));
                fos.close();
            } catch (Exception ignored) {}
        }
    }
}
