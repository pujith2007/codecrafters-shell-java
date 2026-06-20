import java.util.Scanner;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Files;

public class Main {

    public static void main(String[] args) throws Exception {

        Scanner scanner = new Scanner(System.in);

        Set<String> builtins = Set.of("echo", "exit", "type", "pwd", "cd", "jobs");

        Path currentDirectory = Path.of(System.getProperty("user.dir"));

        while (true) {

            System.out.print("$ ");

            String input = scanner.nextLine().trim();

            if (input.equals("exit")) {
                break;
            }

            else if (input.equals("jobs") || input.startsWith("jobs ")) {
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

                String dirName = input.substring(3).trim();

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

                String command = input.substring(5).trim();

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

                File executable = null;

                String path = System.getenv("PATH");
                String[] dirs = path.split(File.pathSeparator);

                for (String dir : dirs) {
                    File file = new File(dir, command);
                    if (file.exists() && file.canExecute()) {
                        executable = file;
                        break;
                    }
                }

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
                    process.waitFor();

                } else {
                    System.out.println(command + ": command not found");
                }
            }
        }
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

                } else if (c == '>') {

                    boolean append = (i + 1 < input.length() && input.charAt(i + 1) == '>');
                    if (append) i++;

                    if (current.length() > 0) {

                        String token = current.toString();

                        if (token.equals("1")) {
                            args.add(append ? "1>>" : "1>");
                        } else if (token.equals("2")) {
                            args.add(append ? "2>>" : "2>");
                        }
                        else {
                            args.add(token);
                            args.add(append ? ">>" : ">");
                        }

                        current.setLength(0);

                    } else {
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