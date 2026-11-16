package ac.core.config;

/**
 * Command line help of the packaged application.
 *
 * <p>Printed by {@code --help} before the Spring context is created, so an operator can read the
 * options of a deployment without starting it.
 */
public final class StartupHelp {

    public static final String HELP_FLAG = "--help";

    public static final String SHORT_HELP_FLAG = "-h";

    private StartupHelp() {
    }

    /** True when the arguments ask for the help text; help wins over every other option. */
    public static boolean requested(String[] args) {
        if (args == null) {
            return false;
        }
        for (String arg : args) {
            if (HELP_FLAG.equals(arg) || SHORT_HELP_FLAG.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    /** Help text shown for {@code --help}. */
    public static String text() {
        return """
                Tabular structured text data manager

                Usage:
                  java -jar otc-clear-testdata.jar [options]
                  java -jar otc-clear-testdata.jar --help

                Mode
                  --tenant --tenant-id=<id>   serve one tenant; <id> matches [a-z0-9-]{1,32}
                  --no-tenant                 single instance mode, tenant fixed to "default"
                  (default)                   single instance mode using app.tenant.id

                Data
                  --init-database             wipe the data of the resolved tenant, then create a fresh
                                              database with the factory definitions (built-in type
                                              sets, platform catalogue and starter products).
                                              Only that tenant directory is touched. The data is not
                                              deleted until YES is typed at the prompt.

                Server
                  --server.port=<port>        HTTP port, default 8080

                Help
                  --help, -h                  print this help and exit without starting the service

                Environment
                  APP_DATA_ROOT               data root used when -Dapp.data-root is absent
                                              (default ./data); accepts an absolute path,
                                              a path relative to the working directory,
                                              classpath:data or projectdir:data

                JVM properties
                  -Dapp.data-root=<dir>       data root, default ./data (or APP_DATA_ROOT)
                  -Dspring.profiles.active=X  X is dev (default) or prod
                  -Dapp.ui.show-tenant=true   show the tenant identifier in the shell

                Examples
                  run-tenant-prod.cmd hkex-otc 8080
                  run-tenant-prod.cmd hkex-otc 8080 --init-database
                  java -Dapp.data-root=/srv/data -jar otc-clear-testdata.jar --tenant --tenant-id=hkex-otc
                """;
    }
}
