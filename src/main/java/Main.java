import java.util.Scanner;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.io.File;
import java.nio.file.Path;

public class Main {

    public static void main(String[] args) throws Exception {

        Scanner scanner = new Scanner(System.in);

        Set<String> builtins = Set.of("echo", "exit", "type", "pwd", "cd");

        Path currentDirectory = Path.of(System.getProperty("user.dir"));

        while (true) {

            System.out.print("$ ");

            String input = scanner.nextLine();

            if (input.equals("exit")) {
                break;
            }

            else if (input.startsWith("echo ")) {

                List<String> parsed = parseCommand(input);

                for (int i = 1; i < parsed.size(); i++) {

                    if (i > 1) {
                        System.out.print(" ");
                    }

                    System.out.print(parsed.get(i));
                }

                System.out.println();
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

                String command = parsed.get(0);

                String[] parts = parsed.toArray(new String[0]);

                String path = System.getenv("PATH");
                String[] dirs = path.split(File.pathSeparator);

                File executable = null;

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

                    pb.inheritIO();

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

        // Inside single quotes: everything is literal
        if (inSingleQuotes) {

            if (c == '\'') {
                inSingleQuotes = false;
            } else {
                current.append(c);
            }

        }

        // Inside double quotes
        else if (inDoubleQuotes) {

            if (c == '\\') {

                if (i + 1 < input.length()) {

                    char next = input.charAt(i + 1);

                    // Only " and \ are escaped in this stage
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

        // Outside quotes
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
    
