package com.eratverbum.stepbible.bootstrap;

import com.tyndalehouse.step.server.STEPTomcatServer;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.io.PrintStream;
import java.io.FileOutputStream;

public class StepServerLauncher {
    public static void main(String[] args) {
        String home = System.getProperty("user.home",
            "/data/data/com.eratverbum.stepbible/files");
        try {
            PrintStream logOut = new PrintStream(
                new FileOutputStream(home + "/step_stdout.log", false), true);
            PrintStream logErr = new PrintStream(
                new FileOutputStream(home + "/step_stderr.log", false), true);
            System.setOut(logOut);
            System.setErr(logErr);

            String tmpdir = System.getProperty("java.io.tmpdir");
            if (tmpdir != null) {
                java.nio.file.Files.createDirectories(java.nio.file.Paths.get(tmpdir));
            }

            STEPTomcatServer server = new STEPTomcatServer(true);

            java.util.ResourceBundle emptyBundle = new java.util.PropertyResourceBundle(
                new java.io.StringReader(""));
            Field[] fields = STEPTomcatServer.class.getDeclaredFields();
            for (Field f : fields) {
                if (f.getType().equals(java.util.ResourceBundle.class)) {
                    f.setAccessible(true);
                    f.set(server, emptyBundle);
                }
            }

            Method start = STEPTomcatServer.class.getDeclaredMethod("start");
            start.setAccessible(true);

            boolean started = false;
            try {
                start.invoke(server);
                started = true;
            } catch (java.lang.reflect.InvocationTargetException e) {
                Throwable cause = e.getCause();
                System.err.println("=== start() threw: " + (cause != null ? cause.getMessage() : "?") + " ===");
            }

            if (!started) {
                System.err.println("Starting Tomcat directly as fallback...");
                startTomcatDirectly();
            }

            Thread.sleep(Long.MAX_VALUE);

        } catch (Exception e) {
            try {
                FileOutputStream fos = new FileOutputStream(home + "/step_crash.log");
                PrintStream ps = new PrintStream(fos, true);
                e.printStackTrace(ps);
                ps.close();
            } catch (Exception ignored) {}
        }
    }

    private static void startTomcatDirectly() throws Exception {
        String warPath = System.getProperty("step.war.path", "step-web");
        int port = Integer.parseInt(System.getProperty("step.war.port", "8989"));
        String contextPath = System.getProperty("step.war.context", "");

        org.apache.catalina.startup.Tomcat tomcat = new org.apache.catalina.startup.Tomcat();
        tomcat.setPort(port);
        tomcat.getHost().setAutoDeploy(false);

        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory(
            java.nio.file.Paths.get(System.getProperty("java.io.tmpdir")), "tomcat");
        tomcat.setBaseDir(dir.toString());

        String cp = (contextPath == null || contextPath.isEmpty()) ? "" : contextPath;
        tomcat.addWebapp(cp, new java.io.File(warPath).getAbsolutePath());
        tomcat.start();

        System.err.println("Tomcat started directly on port " + port + ", war=" + warPath);
        tomcat.getServer().await();
    }
}
