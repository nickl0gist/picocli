package picocli.codegen.aot.graalvm;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code DynamicProxyConfigGenerator} generates a JSON String with the fully qualified interface names for which
 * dynamic proxy classes should be generated at native image build time.
 * <p>
 * Substrate VM doesn't provide machinery for generating and interpreting bytecodes at run time.
 * Therefore all dynamic proxy classes
 * <a href="https://github.com/oracle/graal/blob/master/substratevm/DYNAMIC_PROXY.md">need to be generated</a>
 * at native image build time.
 * </p><p>
 * The output of {@code DynamicProxyConfigGenerator} is intended to be passed to the {@code -H:DynamicProxyConfigurationFiles=/path/to/proxy-config.json}
 * option of the {@code native-image} <a href="https://www.graalvm.org/docs/reference-manual/aot-compilation/">GraalVM utility</a>.
 * This allows picocli-based native image applications that use {@code @Command}-annotated interfaces with
 * {@code @Option} and {@code @Parameters}-annotated methods to define options and positional parameters.
 * </p><p>
 * Alternatively, the generated <a href="https://github.com/oracle/graal/blob/master/substratevm/CONFIGURE.md">configuration</a>
 * files can be supplied to the {@code native-image} tool by placing them in a
 * {@code META-INF/native-image/} directory on the class path, for example, in a JAR file used in the image build.
 * This directory (or any of its subdirectories) is searched for files with the names {@code jni-config.json},
 * {@code reflect-config.json}, {@code proxy-config.json} and {@code resource-config.json}, which are then automatically
 * included in the build. Not all of those files must be present.
 * When multiple files with the same name are found, all of them are included.
 * </p>
 *
 * @since 4.0
 */
public class DynamicProxyConfigGenerator {

    @Command(name = "DynamicProxyConfigGenerator",
            description = {"Generates a JSON file with the resources and resource bundles to include in the native image. " +
                    "The generated JSON file can be passed to the -H:DynamicProxyConfigurationFiles=/path/to/proxy-config.json " +
                    "option of the `native-image` GraalVM utility.",
                    "See https://github.com/oracle/graal/blob/master/substratevm/DYNAMIC_PROXY.md"},
            mixinStandardHelpOptions = true, version = "picocli-codegen DynamicProxyConfigGenerator " + CommandLine.VERSION)
    private static class App implements Callable<Integer> {

        @Parameters(arity = "0..*", description = "Zero or more @Command interfaces or classes with @Command interface subcommands to generate a Graal SubstrateVM proxy-config for.")
        Class<?>[] classes = new Class<?>[0];

        @Option(names = {"-i", "--interface"}, description = "Other fully qualified interface names to generate dynamic proxy classes for in the native image." +
                "This option may be specified multiple times with different interface names. " +
                "Specify multiple comma-separated interface names for dynamic proxies that implement multiple interfaces.")
        String[] interfaces = new String[0];

        @Mixin OutputFileMixin outputFile = new OutputFileMixin();

        public Integer call() throws IOException {
            List<CommandSpec> specs = new ArrayList<CommandSpec>();
            for (Class<?> cls : classes) {
                specs.add(new CommandLine(cls).getCommandSpec());
            }
            String result = DynamicProxyConfigGenerator.generateProxyConfig(specs.toArray(new CommandSpec[0]), interfaces);
            outputFile.write(result);
            return 0;
        }
    }

    /**
     * Runs this class as a standalone application, printing the resulting JSON String to a file or to {@code System.out}.
     * @param args one or more fully qualified class names of {@code @Command}-annotated classes.
     */
    public static void main(String... args) {
        new CommandLine(new App()).execute(args);
    }

    /**
     * Returns a JSON String with the resources and resource bundles to include for the specified
     * {@code CommandSpec} objects.
     *
     * @param specs one or more {@code CommandSpec} objects to inspect for resource bundles
     * @param interfaceClasses other (non-{@code @Command}) fully qualified interface names to generate dynamic proxy classes for
     * @return a JSON String in the <a href="https://github.com/oracle/graal/blob/master/substratevm/DYNAMIC_PROXY.md#manual-configuration">format</a>
     *       required by the {@code -H:DynamicProxyConfigurationFiles=/path/to/proxy-config.json} option of the GraalVM {@code native-image} utility.
     */
    public static String generateProxyConfig(CommandSpec[] specs, String[] interfaceClasses) {
        Visitor visitor = new Visitor(interfaceClasses);
        for (CommandSpec spec : specs) {
            visitor.visitCommandSpec(spec);
        }
        return visitor.toString();
    }

    static final class Visitor {
        List<String> interfaces = new ArrayList<String>();
        List<String> commandInterfaces = new ArrayList<String>();

        Visitor(String[] interfaceClasses) {
            interfaces.addAll(Arrays.asList(interfaceClasses));
        }

        void visitCommandSpec(CommandSpec spec) {
            Object userObject = spec.userObject();
            if (Proxy.isProxyClass(userObject.getClass())) {
                Class<?>[] interfaces = userObject.getClass().getInterfaces();
                String names = "";
                for (Class<?> interf : interfaces) {
                    if (names.length() > 0) { names += ","; }
                    names += interf.getCanonicalName(); // TODO or Class.getName()?
                }
                if (names.length() > 0) {
                    commandInterfaces.add(names);
                }
            } else if (spec.userObject() instanceof Element && ((Element) spec.userObject()).getKind() == ElementKind.INTERFACE) {
                commandInterfaces.add(((Element) spec.userObject()).asType().toString());
            }
            for (CommandSpec mixin : spec.mixins().values()) {
                visitCommandSpec(mixin);
            }
            for (CommandLine sub : spec.subcommands().values()) {
                visitCommandSpec(sub.getCommandSpec());
            }
        }

        @Override
        public String toString() {
            return String.format("" +
                    "[%s%n" +
                    "]%n", all());
        }

        @SuppressWarnings("unchecked")
        private StringBuilder all() {
            return json(commandInterfaces, interfaces);
        }

        private static StringBuilder json(List<String>... stringLists) {
            StringBuilder result = new StringBuilder(1024);
            for (List<String> list : stringLists) {
                for (String str : list) {
                    if (result.length() > 0) {
                        result.append(",");
                    }
                    String[] names = str.split(",");
                    String formatted = "";
                    for (String name : names) {
                        if (formatted.length() > 0) {
                            formatted += ", ";
                        }
                        formatted += '"' + name + '"';
                    }
                    result.append(String.format("%n  [%s]", formatted));
                }
            }
            return result;
        }
    }
}