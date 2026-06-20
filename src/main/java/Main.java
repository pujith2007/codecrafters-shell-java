import java.util.Scanner;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Files;

public class Main {

    static class Job {
        int jobId;
        long pid;
        Process process;
        String commandLine;
    }

    public static void main(String[] args) throws Exception {

        Scanner scanner = new Scanner(System.in);

        Set<String> builtins = Set.of("echo", "exit", "type", "pwd", "cd", "jobs");

        Path currentDirectory = Path.of(System.getProperty("user.dir"));

        List<Job> backgroundJobs = new ArrayList<>();

        while (true) {

            reapBackgroundJobs(backgroundJobs);

            System.out.print("$ ");

            String input = scanner.nextLine();

            if (input.equals("exit")) {
                break;
            }

            else if (input.equals("jobs") || input.startsWith("jobs ")) {

                List<Job> stillRunning = new ArrayList<>();

                int size = backgroundJobs.size();
                for (int i = 0; i < size; i++) {
                    Job job = backgroundJobs.get(i);
                    boolean isAlive = job.process.isAlive();

                    String marker;
                    if (i == size - 1) {
                        marker = "+";
                    } else if (i == size - 2) {
                        marker = "-";
                    } else {
                        marker = " ";
                    }

                    if (isAlive) {
                        String status = "Running";
                        String padding = "                 ";
                        System.out.println("[" + job.jobId + "]" + marker + "  " + status + padding + job.commandLine + " &");
                        stillRunning.add(job);
                    } else {
                        String status = "Done";
                        String padding = "                    ";
                        System.out.println("[" + job.jobId + "]" + marker + "  " + status + padding + job.commandLine);
                    }
                }

                backgroundJobs.clear();
                backgroundJobs.addAll(stillRunning);
                continue;
            }

            else if (input.startsWith("echo ")) {

                List<String> parsed = parseCommand(input);

                int redirectIndex = -1;

                for (int i = 0; i < parsed.size(); i++) {
                    if (parsed.get(i).equals(">")
                        || parsed.get(i).equals("1>")
                        || parsed.get(i).equals("2>")
                        || parsed.get(i).equals("2>>")
                        || parsed.get(i).equals(">>")
                        || parsed.get(i).equals("1>>")) {
                        redirectIndex = i;
                        break;
                    }
                }

                StringBuilder output = new StringBuilder();

                int end = (redirectIndex == -1) ? parsed.size() : redirectIndex;

                for (int i = 1; i < end; i++) {
                    if (i > 1) output.append(" ");
                    output.append(parsed.get(i));
                }

                if (redirectIndex != -1) {

                    String operator = parsed.get(redirectIndex);
                    String fileName = parsed.get(redirectIndex + 1);

                    Path filePath = Path.of(fileName);
                    Files.createDirectories(filePath.toAbsolutePath().getParent());

                    if (operator.equals("2>")) {

                        Files.writeString(filePath, "");
                        System.out.println(output);
                    } else if (operator.equals("2>>")) {

                        Files.writeString(
                                filePath,
                                "",
                                java.nio.file.StandardOpenOption.CREATE,
                                java.nio.file.StandardOpenOption.APPEND);

                        System.out.println(output);
                    } else if (operator.equals(">") || operator.equals("1>")) {

                        Files.writeString(filePath,
                                output.toString() + System.lineSeparator());

                    } else if (operator.equals(">>") || operator.equals("1>>")) {

                        Files.writeString(filePath,
                                output.toString() + System.lineSeparator(),
                                java.nio.file.StandardOpenOption.CREATE,
                                java.nio.file.StandardOpenOption.APPEND);
                    }

                } else {

                    System.out.println(output);
                }

            }

            else if (input.equals("pwd")) {

                System.out.println(currentDirectory.toAbsolutePath());

            }

            else if (input.startsWith("cd ")) {

                String dirName = input.substring(3);

                Path newPath;

                if (dirName.equals("~")) {
                    newPath = Path.of(System.getenv("HOME"));
                } else if (dirName.startsWith("/")) {
                    newPath = Path.of(dirName);
                } else {
                    newPath = currentDirectory.resolve(dirName);
                }

                newPath = newPath.normalize();

                if (newPath.toFile().exists() && newPath.toFile().isDirectory()) {
                    currentDirectory = newPath;
                } else {
                    System.out.println("cd: " + dirName + ": No such file or directory");
                }
            }

            else if (input.startsWith("type ")) {

                String command = input.substring(5);

                if (builtins.contains(command)) {
                    System.out.println(command + " is a shell builtin");
                } else {

                    String path = System.getenv("PATH");
                    String[] dirs = path.split(File.pathSeparator);

                    boolean found = false;

                    for (String dir : dirs) {

                        File file = new File(dir, command);

                        if (file.exists() && file.canExecute()) {
                            System.out.println(command + " is " + file.getAbsolutePath());
                            found = true;
                            break;
                        }
                    }

                    if (!found) {
                        System.out.println(command + ": not found");
                    }
                }
            }

            else {

                List<String> parsed = parseCommand(input);

                int pipeIndex = -1;
                for (int i = 0; i < parsed.size(); i++) {
                    if (parsed.get(i).equals("|")) {
                        pipeIndex = i;
                        break;
                    }
                }

                boolean isBackground = false;
                if (pipeIndex == -1 && !parsed.isEmpty() && parsed.get(parsed.size() - 1).equals("&")) {
                    isBackground = true;
                    parsed.remove(parsed.size() - 1);
                }

                if (pipeIndex != -1) {
                    List<String> leftArgs = new ArrayList<>();
                    List<String> rightArgs = new ArrayList<>();

                    for (int i = 0; i < pipeIndex; i++) {
                        leftArgs.add(parsed.get(i));
                    }
                    for (int i = pipeIndex + 1; i < parsed.size(); i++) {
                        rightArgs.add(parsed.get(i));
                    }

                    if (leftArgs.isEmpty() || rightArgs.isEmpty()) {
                        System.out.println("parse error: near `|'");
                        continue;
                    }

                    File leftExec = findExecutable(leftArgs.get(0));
                    File rightExec = findExecutable(rightArgs.get(0));

                    if (leftExec == null) {
                        System.out.println(leftArgs.get(0) + ": command not found");
                        continue;
                    }
                    if (rightExec == null) {
                        System.out.println(rightArgs.get(0) + ": command not found");
                        continue;
                    }

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

                    new Thread(() -> {
                        try {
                            leftProcess.getInputStream().transferTo(rightProcess.getOutputStream());
                            rightProcess.getOutputStream().close();
                        } catch (Exception ignored) {}
                    }).start();

                    if (isBackground) {
                        Job job = new Job();
                        job.jobId = getNextJobId(backgroundJobs);
                        job.pid = rightProcess.pid();
                        job.process = rightProcess;
                        job.commandLine = String.join(" ", parsed);
                        backgroundJobs.add(job);
                        System.out.println("[" + job.jobId + "] " + job.pid);
                    } else {
                        leftProcess.onExit().join();
                        rightProcess.onExit().join();
                    }
                    continue;
                }

                int redirectIndex = -1;

                for (int i = 0; i < parsed.size(); i++) {
                    if (parsed.get(i).equals(">")
                            || parsed.get(i).equals("2>>")
                            || parsed.get(i).equals("1>")
                            || parsed.get(i).equals("2>")
                            || parsed.get(i).equals(">>")
                            || parsed.get(i).equals("1>>")) {
                        redirectIndex = i;
                        break;
                    }
                }

                List<String> commandArgs = new ArrayList<>();

                for (int i = 0; i < parsed.size(); i++) {
                    if (i == redirectIndex) break;
                    commandArgs.add(parsed.get(i));
                }

                if (commandArgs.isEmpty()) continue;

                String command = commandArgs.get(0);
                String[] parts = commandArgs.toArray(new String[0]);

                File executable = findExecutable(command);

                if (executable != null) {

                    ProcessBuilder pb = new ProcessBuilder(parts);
                    pb.directory(currentDirectory.toFile());

                    if (redirectIndex != -1) {

                        String operator = parsed.get(redirectIndex);
                        String fileName = parsed.get(redirectIndex + 1);

                        File outFile = new File(fileName);

                        if (operator.equals("2>")) {
                            pb.redirectError(outFile);
                            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                        }
                        else if (operator.equals("2>>")) {
                            pb.redirectError(ProcessBuilder.Redirect.appendTo(outFile));
                            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                        }
                        else if (operator.equals(">") || operator.equals("1>")) {
                            pb.redirectOutput(outFile);
                            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                        } else if (operator.equals(">>") || operator.equals("1>>")) {
                            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(outFile));
                            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                        }

                    } else {
                        pb.inheritIO();
                    }

                    Process process = pb.start();

                    if (isBackground) {
                        Job job = new Job();
                        job.jobId = getNextJobId(backgroundJobs);
                        job.pid = process.pid();
                        job.process = process;
                        job.commandLine = String.join(" ", commandArgs);
                        backgroundJobs.add(job);

                        System.out.println("[" + job.jobId + "] " + job.pid);
                    } else {
                        process.waitFor();
                    }

                } else {
                    System.out.println(command + ": command not found");
                }
            }
        }
    }

    private static File findExecutable(String command) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        String[] dirs = path.split(File.pathSeparator);
        for (String dir : dirs) {
            File file = new File(dir, command);
            if (file.exists() && file.canExecute()) {
                return file;
            }
        }
        return null;
    }

    private static int getNextJobId(List<Job> jobs) {
        if (jobs.isEmpty()) {
            return 1;
        }
        int max = 0;
        for (Job job : jobs) {
            if (job.jobId > max) {
                max = job.jobId;
            }
        }
        return max + 1;
    }

    private static void reapBackgroundJobs(List<Job> backgroundJobs) {
        List<Job> stillRunning = new ArrayList<>();

        int size = backgroundJobs.size();
        for (int i = 0; i < size; i++) {
            Job job = backgroundJobs.get(i);
            boolean isAlive = job.process.isAlive();

            String marker;
            if (i == size - 1) {
                marker = "+";
            } else if (i == size - 2) {
                marker = "-";
            } else {
                marker = " ";
            }

            if (!isAlive) {
                String status = "Done";
                String padding = "                    ";
                System.out.println("[" + job.jobId + "]" + marker + "  " + status + padding + job.commandLine);
            } else {
                stillRunning.add(job);
            }
        }

        backgroundJobs.clear();
        backgroundJobs.addAll(stillRunning);
    }

    private static List<String> parseCommand(String input) {

        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;

        for (int i = 0; i < input.length(); i++) {

            char c = input.charAt(i);

            if (inSingleQuotes) {
                if (c == '\'') inSingleQuotes = false;
                else current.append(c);
            }

            else if (inDoubleQuotes) {

                if (c == '\\') {

                    if (i + 1 < input.length()) {
                        char next = input.charAt(i + 1);

                        if (next == '"' || next == '\\') {
                            current.append(next);
                            i++;
                        } else {
                            current.append('\\');
                            current.append(next);
                            i++;
                        }
                    } else {
                        current.append('\\');
                    }

                } else if (c == '"') {
                    inDoubleQuotes = false;
                } else {
                    current.append(c);
                }
            }

            else {

                if (c == '\\') {

                    if (i + 1 < input.length()) {
                        current.append(input.charAt(i + 1));
                        i++;
                    }

                } else if (c == '\'') {
                    inSingleQuotes = true;

                } else if (c == '"') {
                    inDoubleQuotes = true;

                } else if (c == '|' || c == '>') {

                    if (current.length() > 0) {
                        args.add(current.toString());
                        current.setLength(0);
                    }

                    if (c == '|') {
                        args.add("|");
                    } else {
                        boolean append = (i + 1 < input.length() && input.charAt(i + 1) == '>');
                        if (append) i++;

                        args.add(append ? ">>" : ">");
                    }

                } else if (Character.isWhitespace(c)) {

                    if (current.length() > 0) {
                        args.add(current.toString());
                        current.setLength(0);
                    }

                } else {
                    current.append(c);
                }
            }
        }

        if (current.length() > 0) {
            args.add(current.toString());
        }

        return args;
    }
}