import java.util.Scanner;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.*;

public class Main {

    private static final Set<String> BUILTINS = Set.of("echo", "exit", "type", "pwd", "cd", "jobs");

    static class Job {
        int jobId;
        long pid;
        Process process;
        String commandLine;
    }

    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);
        Path currentDirectory = Path.of(System.getProperty("user.dir"));
        List<Job> backgroundJobs = new ArrayList<>();

        while (true) {
            reapBackgroundJobs(backgroundJobs);

            System.out.print("$ ");

            String input = scanner.nextLine().trim();
            if (input.isEmpty()) continue;

            if (input.equals("exit")) break;

            List<String> parsed = parseCommand(input);

            // === PIPELINE DETECTION - MUST BE FIRST ===
            int pipeIndex = -1;
            for (int i = 0; i < parsed.size(); i++) {
                if (parsed.get(i).equals("|")) {
                    pipeIndex = i;
                    break;
                }
            }

            if (pipeIndex != -1) {
                handlePipeline(parsed, pipeIndex, currentDirectory);
                continue;
            }

            // Background job?
            boolean isBackground = false;
            if (!parsed.isEmpty() && parsed.get(parsed.size() - 1).equals("&")) {
                isBackground = true;
                parsed.remove(parsed.size() - 1);
            }

            if (parsed.isEmpty()) continue;

            String command = parsed.get(0);

            // Built-in commands (non-pipeline)
            if (BUILTINS.contains(command)) {
                if ("echo".equals(command)) {
                    handleEcho(parsed);
                } else {
                    executeBuiltin(parsed, null, System.out, currentDirectory);
                }
                continue;
            }

            // External command
            File executable = findExecutable(command);
            if (executable == null) {
                System.out.println(command + ": command not found");
                continue;
            }

            int redirectIndex = findRedirectIndex(parsed);
            List<String> commandArgs = getCommandArgs(parsed, redirectIndex);

            ProcessBuilder pb = new ProcessBuilder(commandArgs);
            pb.directory(currentDirectory.toFile());
            setupRedirects(pb, parsed, redirectIndex);

            Process process = pb.start();

            if (isBackground) {
                Job job = createJob(backgroundJobs, process, commandArgs);
                System.out.println("[" + job.jobId + "] " + job.pid);
            } else {
                process.waitFor();
            }
        }
    }

    // ==================== PIPELINES ====================
    private static void handlePipeline(List<String> parsed, int pipeIndex, Path currentDirectory) throws Exception {
        List<String> leftArgs = parsed.subList(0, pipeIndex);
        List<String> rightArgs = parsed.subList(pipeIndex + 1, parsed.size());

        if (leftArgs.isEmpty() || rightArgs.isEmpty()) {
            System.out.println("parse error: near `|'");
            return;
        }

        String leftCmd = leftArgs.get(0);
        String rightCmd = rightArgs.get(0);

        boolean leftBuiltin = BUILTINS.contains(leftCmd);
        boolean rightBuiltin = BUILTINS.contains(rightCmd);

        if (!leftBuiltin && findExecutable(leftCmd) == null) {
            System.out.println(leftCmd + ": command not found");
            return;
        }
        if (!rightBuiltin && findExecutable(rightCmd) == null) {
            System.out.println(rightCmd + ": command not found");
            return;
        }

        if (leftBuiltin && rightBuiltin) {
            executeBuiltin(leftArgs, null, new ByteArrayOutputStream(), currentDirectory);
            executeBuiltin(rightArgs, null, System.out, currentDirectory);
            return;
        }

        if (leftBuiltin) {
            // Builtin | External  (e.g. echo ... | wc)
            try (PipedOutputStream pos = new PipedOutputStream();
                 PipedInputStream pis = new PipedInputStream(pos)) {

                Thread leftThread = new Thread(() -> {
                    try {
                        executeBuiltin(leftArgs, null, pos, currentDirectory);
                    } catch (Exception ignored) {}
                    finally { try { pos.close(); } catch (Exception ignored) {} }
                });
                leftThread.start();

                ProcessBuilder rightPb = new ProcessBuilder(rightArgs);
                rightPb.directory(currentDirectory.toFile());
                rightPb.redirectInput(ProcessBuilder.Redirect.PIPE);
                rightPb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                rightPb.redirectError(ProcessBuilder.Redirect.INHERIT);

                Process rightProcess = rightPb.start();

                // Copy from builtin to right process
                try (OutputStream rightIn = rightProcess.getOutputStream()) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = pis.read(buf)) != -1) {
                        rightIn.write(buf, 0, len);
                        rightIn.flush();
                    }
                }

                rightProcess.waitFor();
                leftThread.join();
            }
        } else if (rightBuiltin) {
            // External | Builtin
            ProcessBuilder leftPb = new ProcessBuilder(leftArgs);
            leftPb.directory(currentDirectory.toFile());
            leftPb.redirectOutput(ProcessBuilder.Redirect.PIPE);
            leftPb.redirectError(ProcessBuilder.Redirect.INHERIT);

            Process leftProcess = leftPb.start();

            Thread rightThread = new Thread(() -> {
                try {
                    executeBuiltin(rightArgs, leftProcess.getInputStream(), System.out, currentDirectory);
                } catch (Exception ignored) {}
            });
            rightThread.start();

            leftProcess.waitFor();
            rightThread.join();
        } else {
            // External | External
            ProcessBuilder leftPb = new ProcessBuilder(leftArgs);
            ProcessBuilder rightPb = new ProcessBuilder(rightArgs);
            leftPb.directory(currentDirectory.toFile());
            rightPb.directory(currentDirectory.toFile());

            leftPb.redirectOutput(ProcessBuilder.Redirect.PIPE);
            rightPb.redirectInput(ProcessBuilder.Redirect.PIPE);
            rightPb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            rightPb.redirectError(ProcessBuilder.Redirect.INHERIT);

            Process leftProcess = leftPb.start();
            Process rightProcess = rightPb.start();

            Thread copier = new Thread(() -> {
                try {
                    leftProcess.getInputStream().transferTo(rightProcess.getOutputStream());
                } catch (Exception ignored) {}
                finally {
                    try { rightProcess.getOutputStream().close(); } catch (Exception ignored) {}
                }
            });
            copier.start();

            rightProcess.waitFor();
            if (leftProcess.isAlive()) leftProcess.destroy();
            copier.join();
        }
    }

    // ==================== HELPERS ====================
    private static void handleEcho(List<String> parsed) {
        int redirectIndex = findRedirectIndex(parsed);
        StringBuilder sb = new StringBuilder();
        int end = redirectIndex == -1 ? parsed.size() : redirectIndex;
        for (int i = 1; i < end; i++) {
            if (i > 1) sb.append(" ");
            sb.append(parsed.get(i));
        }
        if (redirectIndex != -1) {
            // simplified redirect handling for echo
            System.out.println(sb);
        } else {
            System.out.println(sb);
        }
    }

    private static void executeBuiltin(List<String> args, InputStream stdin, OutputStream stdout, Path cwd) throws Exception {
        if (args.isEmpty()) return;
        String cmd = args.get(0);
        PrintStream out = stdout != null ? new PrintStream(stdout) : System.out;

        if ("echo".equals(cmd)) {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < args.size(); i++) {
                if (i > 1) sb.append(" ");
                sb.append(args.get(i));
            }
            out.println(sb);
        } else if ("pwd".equals(cmd)) {
            out.println(cwd.toAbsolutePath());
        } else if ("type".equals(cmd) && args.size() > 1) {
            String target = args.get(1);
            if (BUILTINS.contains(target)) {
                out.println(target + " is a shell builtin");
            } else {
                File f = findExecutable(target);
                out.println(f != null ? target + " is " + f.getAbsolutePath() : target + ": not found");
            }
        }

        if (stdin != null) {
            try { stdin.transferTo(OutputStream.nullOutputStream()); } catch (Exception ignored) {}
        }
        out.flush();
    }

    private static Job createJob(List<Job> jobs, Process p, List<String> args) {
        Job j = new Job();
        j.jobId = getNextJobId(jobs);
        j.pid = p.pid();
        j.process = p;
        j.commandLine = String.join(" ", args);
        jobs.add(j);
        return j;
    }

    private static void reapBackgroundJobs(List<Job> jobs) {
        List<Job> alive = new ArrayList<>();
        for (Job j : jobs) {
            if (isAlive(j.process)) {
                alive.add(j);
            } else {
                System.out.println("[" + j.jobId + "]+  Done                    " + j.commandLine);
            }
        }
        jobs.clear();
        jobs.addAll(alive);
    }

    private static boolean isAlive(Process p) {
        try { p.exitValue(); return false; } catch (IllegalThreadStateException e) { return true; }
    }

    private static int getNextJobId(List<Job> jobs) {
        return jobs.isEmpty() ? 1 : jobs.stream().mapToInt(j -> j.jobId).max().orElse(0) + 1;
    }

    private static int findRedirectIndex(List<String> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (t.equals(">") || t.equals(">>") || t.equals("1>") || t.equals("1>>") ||
                t.equals("2>") || t.equals("2>>")) return i;
        }
        return -1;
    }

    private static List<String> getCommandArgs(List<String> parsed, int redirectIndex) {
        List<String> args = new ArrayList<>();
        for (int i = 0; i < parsed.size() && (redirectIndex == -1 || i < redirectIndex); i++) {
            args.add(parsed.get(i));
        }
        return args;
    }

    private static void setupRedirects(ProcessBuilder pb, List<String> parsed, int redirectIndex) {
        if (redirectIndex == -1) {
            pb.inheritIO();
            return;
        }
        // Add your full redirect logic here if needed
        pb.inheritIO(); // fallback
    }

    private static File findExecutable(String cmd) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(File.pathSeparator)) {
            File f = new File(dir, cmd);
            if (f.exists() && f.canExecute()) return f;
        }
        return null;
    }

    private static List<String> parseCommand(String input) {
        List<String> args = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean sq = false, dq = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (sq) {
                if (c == '\'') sq = false; else cur.append(c);
            } else if (dq) {
                if (c == '"') dq = false;
                else if (c == '\\' && i+1 < input.length()) cur.append(input.charAt(++i));
                else cur.append(c);
            } else if (c == '\'') sq = true;
            else if (c == '"') dq = true;
            else if (c == '|' || c == '>') {
                if (cur.length() > 0) args.add(cur.toString());
                cur.setLength(0);
                if (c == '|') args.add("|");
                else {
                    if (i+1 < input.length() && input.charAt(i+1) == '>') { args.add(">>"); i++; }
                    else args.add(">");
                }
            } else if (Character.isWhitespace(c)) {
                if (cur.length() > 0) args.add(cur.toString());
                cur.setLength(0);
            } else cur.append(c);
        }
        if (cur.length() > 0) args.add(cur.toString());
        return args;
    }
}