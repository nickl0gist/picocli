package picocli.codegen.aot.graalvm;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Unmatched;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.TimeUnit;

/**
 * This command's `run` method uses an extra ResourceBundle with base="some.extra.bundle".
 * Make sure to add this to the native image with the ResourceConfigGenerator tool.
 */
@Command(name = "example",
        mixinStandardHelpOptions = true,
        subcommands = CommandLine.HelpCommand.class,
        resourceBundle = "picocli.codegen.aot.graalvm.exampleResources",
        version = {
                "Example " + CommandLine.VERSION,
                "JVM: ${java.version} (${java.vendor} ${java.vm.name} ${java.vm.version})",
                "OS: ${os.name} ${os.version} ${os.arch}"
        })
public class Example implements Runnable {

    @Command public static class ExampleMixin {

        @Option(names = "-l")
        int length;
    }

    @Option(names = "-t")
    final TimeUnit timeUnit = TimeUnit.SECONDS;

    @Parameters(index = "0")
    File file;

    @Spec
    CommandSpec spec;

    @Mixin
    ExampleMixin mixin;

    @Unmatched
    final List<String> unmatched = new ArrayList<String>();

    private int minimum;
    private List<File> otherFiles;

    @Command(resourceBundle = "picocli.codegen.aot.graalvm.exampleMultiplyResources")
    int multiply(@Option(names = "--count") int count,
                 @Parameters int multiplier) {
        System.out.println("Result is " + count * multiplier);
        return count * multiplier;
    }

    @Option(names = "--minimum")
    public void setMinimum(int min) {
        if (min < 0) {
            throw new ParameterException(spec.commandLine(), "Minimum must be a positive integer");
        }
        minimum = min;
    }

    @Parameters(index = "1..*")
    public void setOtherFiles(List<File> otherFiles) {
        for (File f : otherFiles) {
            if (!f.exists()) {
                throw new ParameterException(spec.commandLine(), "File " + f.getAbsolutePath() + " must exist");
            }
        }
        this.otherFiles = otherFiles;
    }

    public void run() {
        System.out.printf("timeUnit=%s, length=%s, file=%s, unmatched=%s, minimum=%s, otherFiles=%s%n",
                timeUnit, mixin.length, file, unmatched, minimum, otherFiles);
        System.out.println("Getting value from some.extra.bundle:");
        ResourceBundle bundle = ResourceBundle.getBundle("some.extra.bundle");
        System.out.println("Found bundle. Its value for 'key' is: " + bundle.getString("key"));
    }

    public static void main(String[] args) {
        new CommandLine(new Example()).execute(args);
    }
}
