import org.apache.commons.io.FileUtils

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

def jakartaVersion = request.properties["jakartaVersion"].trim()
def profile = request.properties["profile"].trim().toLowerCase()
def javaVersion = request.properties["javaVersion"].trim()
def runtime = request.properties["runtime"].trim().toLowerCase()
def docker = request.properties["docker"].trim().toLowerCase()

def outputDirectory = new File(request.getOutputDirectory(), request.getArtifactId())

validateInput(jakartaVersion, profile, javaVersion, runtime, docker, outputDirectory)
generateRuntime(runtime, jakartaVersion, docker, outputDirectory)
bindEEPackage(jakartaVersion, outputDirectory)
generateDocker(docker, runtime, outputDirectory)
chmod(outputDirectory.toPath().resolve("mvnw").toFile())
printSummary()

private validateInput(jakartaVersion, profile, javaVersion, runtime, docker, File outputDirectory) {
    def validJakartaVersions = ['8', '9', '9.1', '10', '11']
    if (!(jakartaVersion in validJakartaVersions)) {
        FileUtils.forceDelete(outputDirectory)
        throw new RuntimeException("Failed, valid Jakarta EE versions are: ${validJakartaVersions}")
    }

    def validProfiles = ['core', 'web', 'full']
    if (!(profile in validProfiles)) {
        FileUtils.forceDelete(outputDirectory)
        throw new RuntimeException("Failed, valid Jakarta EE profiles are: ${validProfiles}")
    }
    def validJavaVersions = ['8', '11', '17', '21']
    if (!(javaVersion in validJavaVersions)) {
        FileUtils.forceDelete(outputDirectory)
        throw new RuntimeException("Failed, valid Java SE versions are: ${validJavaVersions}")
    }

    def validRuntimes = ['none', 'glassfish', 'open-liberty', 'payara', 'tomee', 'wildfly']
    if (!(runtime in validRuntimes)) {
        FileUtils.forceDelete(outputDirectory)
        throw new RuntimeException("Failed, valid runtime values are: ${validRuntimes}")
    }

    def validDockerOptions = ['yes', 'no']
    if (!(docker in validDockerOptions)) {
        FileUtils.forceDelete(outputDirectory)
        throw new RuntimeException("Failed, valid Docker options are: ${validDockerOptions}")
    }

    if (profile == 'core' && !(jakartaVersion in ['10', '11'])) {
        FileUtils.forceDelete(outputDirectory)
        throw new RuntimeException("Failed, the Core Profile is only supported for Jakarta EE 10 and 11")
    }
    if ((javaVersion == '8') && (jakartaVersion in ['10', '11'])) {
        FileUtils.forceDelete(outputDirectory)
        throw new RuntimeException("Failed, Jakarta EE 10 and 11 do not support Java SE 8")
    }

    if ((javaVersion == '11') && (jakartaVersion == '11')) {
        FileUtils.forceDelete(outputDirectory)
        throw new RuntimeException("Failed, Jakarta EE 11 does not support Java SE 11")
    }

    // TODO: only support GlassFish for EE 11 at start, replace Milestone release once released
    if (jakartaVersion == '11' && !(runtime in ['none', 'glassfish'])) {
        FileUtils.forceDelete(outputDirectory)
        throw new RuntimeException("Failed, Currently only glassfish is supported for Jakarta EE 11")
    }

    if (runtime == 'payara' && (jakartaVersion != '8') && (javaVersion == '8')) {
        FileUtils.forceDelete(outputDirectory)
        throw new RuntimeException("Failed, Payara 6 does not support Java SE 8")
    }

    if (runtime == 'glassfish') {
        if (profile == 'core') {
            FileUtils.forceDelete(outputDirectory)
            throw new RuntimeException("Failed, GlassFish does not support the Core Profile")
        }

        if ((jakartaVersion != '8') && (javaVersion == '8')) {
            FileUtils.forceDelete(outputDirectory)
            throw new RuntimeException("Failed, GlassFish 7 does not support Java SE 8")
        }
    }

    if (runtime == 'tomee') {
        if (jakartaVersion in ['10', '11']) {
            FileUtils.forceDelete(outputDirectory)
            throw new RuntimeException("Failed, TomEE does not support Jakarta EE 10 or 11")
        }

        if (jakartaVersion == '9') {
            FileUtils.forceDelete(outputDirectory)
            throw new RuntimeException("Failed, TomEE is certified against Jakarta EE 9.1, but not Jakarta EE 9")
        }

        if (profile != 'web') {
            FileUtils.forceDelete(outputDirectory)
            throw new RuntimeException("Failed, TomEE does not support the full and Core Profiles")
        }

        if ((jakartaVersion != '8') && (javaVersion == '8')) {
            FileUtils.forceDelete(outputDirectory)
            throw new RuntimeException("Failed, TomEE 9 does not support Java SE 8")
        }
    }

    if (runtime == 'wildfly') {
        if ((jakartaVersion == '9') || (jakartaVersion == '9.1')) {
            FileUtils.forceDelete(outputDirectory)
            throw new RuntimeException("Failed, WildFly does not offer a release for Jakarta EE 9 or Jakarta EE 9.1")
        }
    }
}

private generateRuntime(runtime, jakartaVersion, docker, File outputDirectory) {
    switch (runtime) {
        case "glassfish": println "Generating code for GlassFish"
            if (docker.equalsIgnoreCase("yes")) {
                println "WARNING: GlassFish does not yet support Docker"
                FileUtils.forceDelete(new File(outputDirectory, "Dockerfile"))
            }
            break

        case "tomee": println "Generating code for TomEE"
            break

        case "payara": println "Generating code for Payara"
            break

        case "wildfly": println "Generating code for WildFly"
            break

        case "open-liberty": println "Generating code for Open Liberty"
            break

        default: println "No runtime will be included in the sample"
    }

    if (runtime != 'open-liberty') {
        FileUtils.forceDelete(new File(outputDirectory, "src/main/liberty"))
    }
}

static void bindEEPackage(String jakartaVersion, File outputDirectory) throws IOException {
    String eePackage = "jakarta"
    if (jakartaVersion == '8') {
        eePackage = "javax"
    }

    println "Binding EE package: $eePackage"

    File[] files = outputDirectory.listFiles()
    if (files != null) {
        files.each { file ->
            traverseFiles(file, eePackage)
        }
    }
}

private static void traverseFiles(File file, String eePackage) throws IOException {
    if (file.isDirectory()) {
        File[] files = file.listFiles()
        if (files != null) {
            files.each { subFile ->
                traverseFiles(subFile, eePackage)
            }
        }
    } else if (file.isFile() && file.getName().matches(".*\\.(xml|java)") && !file.getName().endsWith("pom.xml")) {
        processFile(file, eePackage)
    }
}

private static void processFile(File file, String eePackage) throws IOException {
    Path filePath = file.toPath()
    String content = new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8)
    String replacedContent = content.replaceAll('\\$\\{eePackage}', eePackage)

    Files.write(filePath, replacedContent.getBytes(StandardCharsets.UTF_8))
}

private generateDocker(docker, runtime, File outputDirectory) {
    if (docker.equalsIgnoreCase("no")) {
        println "Docker support was not requested"
        FileUtils.forceDelete(new File(outputDirectory, "Dockerfile"))
    } else if (runtime.equalsIgnoreCase("none")) {
        println "WARNING: Docker support is not possible without choosing a runtime"
        FileUtils.forceDelete(new File(outputDirectory, "Dockerfile"))
    }
}

private chmod(File mvnw) {
    def isWindows = System.properties['os.name'].toLowerCase().contains('windows')

    if (!isWindows) {
        println "Running chmod on " + mvnw.getName()

        def processBuilder = new ProcessBuilder("chmod", "+x", mvnw.getAbsolutePath())
        def process = processBuilder.start()
        def exitCode = process.waitFor()

        if (exitCode != 0) {
            println "WARNING: Failed to set executable permission on file: " + mvnw.getAbsolutePath()
        }
    }
}

private printSummary() {
    println "The README.md file in the " + request.properties["artifactId"] + " directory explains how to run the generated application"
}
