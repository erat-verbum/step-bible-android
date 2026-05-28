package com.eratverbum.stepbible.bootstrap;

import com.tyndalehouse.step.server.STEPTomcatServer;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.io.PrintStream;
import java.io.FileOutputStream;
import java.io.File;
import java.nio.file.Files;

public class StepServerLauncher {
    private static final String DEFAULT_WAR_PATH = "step-web";
    private static final String DEFAULT_PORT = "8989";

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

            linkJswordData(home);

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

            new java.util.concurrent.CountDownLatch(1).await();

        } catch (Exception e) {
            try {
                FileOutputStream fos = new FileOutputStream(home + "/step_crash.log");
                PrintStream ps = new PrintStream(fos, true);
                e.printStackTrace(ps);
                ps.close();
            } catch (Exception ignored) {}
        }
    }

    private static void linkJswordData(String home) {
        try {
            File jswordHome = new File(home, ".jsword");
            File jswordSource = new File(home, "step/homes/jsword");
            File swordSource = new File(home, "step/homes/sword");
            if (!jswordSource.exists()) return;
            jswordHome.mkdirs();

            // modules/ -> step/homes/sword/modules/
            linkDir(new File(jswordHome, "modules"), new File(swordSource, "modules"));

            // mods.d/ - copy conf files from sword data
            File modsDest = new File(jswordHome, "mods.d");
            File modsSource = new File(swordSource, "mods.d");
            if (modsSource.exists()) {
                modsDest.mkdirs();
                File[] confs = modsSource.listFiles((d, n) -> n.endsWith(".conf"));
                if (confs != null) {
                    for (File conf : confs) {
                        File dest = new File(modsDest, conf.getName());
                        if (!dest.exists()) {
                            Files.copy(conf.toPath(), dest.toPath());
                        }
                    }
                }
                System.err.println("Copied mods.d to jsword");
            }

            // lucene/Sword/ -> step/homes/jsword/lucene/Sword/
            linkDir(new File(jswordHome, "lucene/Sword"), new File(jswordSource, "lucene/Sword"));

            // step/ -> step/homes/jsword/step/ (includes entities, ordinal_strongs.dat, etc.)
            linkDir(new File(jswordHome, "step"), new File(jswordSource, "step"));

        } catch (Exception e) {
            System.err.println("Failed to link jsword data: " + e.getMessage());
        }
    }

    private static void copyRecursive(File src, File dst) throws Exception {
        if (src.isDirectory()) {
            dst.mkdirs();
            File[] children = src.listFiles();
            if (children != null) {
                for (File c : children) copyRecursive(c, new File(dst, c.getName()));
            }
        } else {
            dst.getParentFile().mkdirs();
            java.nio.file.Files.copy(src.toPath(), dst.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void linkDir(File link, File target) throws Exception {
        if (!target.exists()) return;
        link.getParentFile().mkdirs();
        // Remove if exists (JSword may have created it)
        if (link.exists()) {
            if (Files.isSymbolicLink(link.toPath())) {
                Files.delete(link.toPath());
            } else {
                deleteRecursive(link);
            }
        }
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath().toAbsolutePath());
        } catch (Exception e) {
            System.err.println("Symlink failed, copying: " + e.getMessage());
            copyRecursive(target, link);
        }
    }

    private static void deleteRecursive(File f) throws Exception {
        if (Files.isSymbolicLink(f.toPath())) {
            Files.delete(f.toPath());
            return;
        }
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursive(c);
            }
        }
        f.delete();
    }

    private static void startTomcatDirectly() throws Exception {
        String warPath = System.getProperty("step.war.path", DEFAULT_WAR_PATH);
        int port = Integer.parseInt(System.getProperty("step.war.port", DEFAULT_PORT));
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
