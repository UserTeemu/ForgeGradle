package net.minecraftforge.gradle.dev;

import static net.minecraftforge.gradle.dev.DevConstants.*;
import groovy.lang.Closure;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

import net.minecraftforge.gradle.CopyInto;
import net.minecraftforge.gradle.common.Constants;
import net.minecraftforge.gradle.delayed.DelayedFile;
import net.minecraftforge.gradle.tasks.ApplyS2STask;
import net.minecraftforge.gradle.tasks.DecompileTask;
import net.minecraftforge.gradle.tasks.ExtractS2SRangeTask;
import net.minecraftforge.gradle.tasks.GenSrgTask;
import net.minecraftforge.gradle.tasks.ProcessJarTask;
import net.minecraftforge.gradle.tasks.ProcessSrcJarTask;
import net.minecraftforge.gradle.tasks.RemapSourcesTask;
import net.minecraftforge.gradle.tasks.abstractutil.DelayedJar;
import net.minecraftforge.gradle.tasks.abstractutil.ExtractTask;
import net.minecraftforge.gradle.tasks.abstractutil.FileFilterTask;
import net.minecraftforge.gradle.tasks.dev.FMLVersionPropTask;
import net.minecraftforge.gradle.tasks.dev.GenBinaryPatches;
import net.minecraftforge.gradle.tasks.dev.GenDevProjectsTask;
import net.minecraftforge.gradle.tasks.dev.GeneratePatches;
import net.minecraftforge.gradle.tasks.dev.ObfuscateTask;
import net.minecraftforge.gradle.tasks.dev.SubprojectTask;

import org.gradle.api.DefaultTask;
import org.gradle.api.Task;
import org.gradle.api.file.ConfigurableFileTree;
import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.java.archives.Manifest;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.Delete;
import org.gradle.api.tasks.bundling.Zip;

public class McpcDevPlugin extends DevBasePlugin
{
    @Override
    public void applyPlugin()
    {
        super.applyPlugin();

        // set folders
        getExtension().setFmlDir("forge/fml");
        getExtension().setForgeDir("forge");
        getExtension().setBukkitDir("bukkit");
        
        // configure genSrg task.
        GenSrgTask genSrgTask = (GenSrgTask) project.getTasks().getByName("genSrgs");
        {
            // find all the exc & srg files in the resources.
            for (File f : project.fileTree(delayedFile(DevConstants.FML_RESOURCES).call()).getFiles())
            {
                if(f.getPath().endsWith(".exc"))
                    genSrgTask.addExtraExc(f);
                else if(f.getPath().endsWith(".srg"))
                    genSrgTask.addExtraSrg(f);
            }
            
            for (File f : project.fileTree(delayedFile(DevConstants.FORGE_RESOURCES).call()).getFiles())
            {
                if(f.getPath().endsWith(".exc"))
                    genSrgTask.addExtraExc(f);
                else if(f.getPath().endsWith(".srg"))
                    genSrgTask.addExtraSrg(f);
            }
            
            for (File f : project.fileTree(delayedFile(DevConstants.MCPC_RESOURCES).call()).getFiles())
            {
                if(f.getPath().endsWith(".exc"))
                    genSrgTask.addExtraExc(f);
                else if(f.getPath().endsWith(".srg"))
                    genSrgTask.addExtraSrg(f);
            }
        }
        
        createJarProcessTasks();
        createProjectTasks();
        createEclipseTasks();
        createMiscTasks();
        createSourceCopyTasks();
        createPackageTasks();

        // the master setup task.
        Task task = makeTask("setupMcpc", DefaultTask.class);
        task.dependsOn("extractMcpcSources", "generateProjects", "eclipse", "copyAssets");
        task.setGroup("MCPC");
        
        // clean packages
        {
            Delete del = makeTask("cleanPackages", Delete.class);
            del.delete("build/distributions");
        }

//        // the master task.
        task = makeTask("buildPackages");
        //task.dependsOn("launch4j", "createChangelog", "packageUniversal", "packageInstaller", "genJavadocs");
        task.dependsOn("cleanPackages", "packageUniversal", "packageInstaller");
        task.setGroup("MCPC");
    }
    
    @Override
    protected final DelayedFile getDevJson()
    {
        return delayedFile(DevConstants.MCPC_JSON_DEV);
    }
    
    protected void createJarProcessTasks()
    {
        ProcessJarTask task2 = makeTask("deobfuscateJar", ProcessJarTask.class);
        {
            task2.setInJar(delayedFile(Constants.JAR_MERGED));
            task2.setOutCleanJar(delayedFile(JAR_SRG_MCPC));
            task2.setSrg(delayedFile(JOINED_SRG));
            task2.setExceptorCfg(delayedFile(JOINED_EXC));
            task2.setExceptorJson(delayedFile(EXC_JSON));
            task2.addTransformerClean(delayedFile(FML_RESOURCES + "/fml_at.cfg"));
            task2.addTransformerClean(delayedFile(FORGE_RESOURCES + "/forge_at.cfg"));
            task2.setApplyMarkers(true);
            task2.dependsOn("downloadMcpTools", "mergeJars");
        }

        DecompileTask task3 = makeTask("decompile", DecompileTask.class);
        {
            task3.setInJar(delayedFile(JAR_SRG_MCPC));
            task3.setOutJar(delayedFile(ZIP_DECOMP_MCPC));
            task3.setFernFlower(delayedFile(Constants.FERNFLOWER));
            task3.setPatch(delayedFile(MCP_PATCH_DIR));
            task3.setAstyleConfig(delayedFile(ASTYLE_CFG));
            task3.dependsOn("downloadMcpTools", "deobfuscateJar");
        }
        
        ProcessSrcJarTask task4 = makeTask("forgePatchJar", ProcessSrcJarTask.class);
        {
            task4.setInJar(delayedFile(ZIP_DECOMP_MCPC));
            task4.setOutJar(delayedFile(ZIP_FORGED_MCPC));
            task4.addStage("fml", delayedFile(FML_PATCH_DIR), delayedFile(FML_SOURCES), delayedFile(FML_RESOURCES), delayedFile("{MAPPINGS_DIR}/patches/Start.java"), delayedFile(DEOBF_DATA), delayedFile(FML_VERSIONF));
            task4.addStage("forge", delayedFile(FORGE_PATCH_DIR), delayedFile(FORGE_SOURCES), delayedFile(FORGE_RESOURCES));
            task4.addStage("bukkit", null, delayedFile(BUKKIT_SOURCES));
            task4.setDoesCache(false);
            task4.setMaxFuzz(2);
            task4.dependsOn("decompile", "compressDeobfData", "createVersionPropertiesFML");
        }

        RemapSourcesTask task6 = makeTask("remapCleanJar", RemapSourcesTask.class);
        {
            task6.setInJar(delayedFile(ZIP_FORGED_MCPC));
            task6.setOutJar(delayedFile(REMAPPED_CLEAN));
            task6.setMethodsCsv(delayedFile(METHODS_CSV));
            task6.setFieldsCsv(delayedFile(FIELDS_CSV));
            task6.setParamsCsv(delayedFile(PARAMS_CSV));
            task6.setDoesCache(true);
            task6.setNoJavadocs();
            task6.dependsOn("forgePatchJar");
        }
         
        task4 = makeTask("mcpcPatchJar", ProcessSrcJarTask.class);
        {
            //task4.setInJar(delayedFile(ZIP_FORGED_MCPC)); UNCOMMENT FOR SRG NAMES
            task4.setInJar(delayedFile(REMAPPED_CLEAN));
            task4.setOutJar(delayedFile(ZIP_PATCHED_MCPC));
            task4.addStage("MCPC", delayedFile(MCPC_PATCH_DIR));
            task4.setDoesCache(false);
            task4.setMaxFuzz(2);
            task4.dependsOn("forgePatchJar");
        }
        
        task6 = makeTask("remapMcpcJar", RemapSourcesTask.class);
        {
            task6.setInJar(delayedFile(ZIP_PATCHED_MCPC));
            task6.setOutJar(delayedFile(ZIP_RENAMED_MCPC));
            task6.setMethodsCsv(delayedFile(METHODS_CSV));
            task6.setFieldsCsv(delayedFile(FIELDS_CSV));
            task6.setParamsCsv(delayedFile(PARAMS_CSV));
            task6.setDoesCache(true);
            task6.setNoJavadocs();
            task6.dependsOn("mcpcPatchJar");
        }
    }
    
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void createSourceCopyTasks()
    {
        ExtractTask task = makeTask("extractCleanResources", ExtractTask.class);
        {
            task.exclude(JAVA_FILES);
            task.setIncludeEmptyDirs(false);
            task.from(delayedFile(REMAPPED_CLEAN));
            task.into(delayedFile(ECLIPSE_CLEAN_RES));
            task.dependsOn("extractWorkspace", "remapCleanJar");
        }

        task = makeTask("extractCleanSource", ExtractTask.class);
        {
            task.include(JAVA_FILES);
            task.setIncludeEmptyDirs(false);
            task.from(delayedFile(REMAPPED_CLEAN));
            task.into(delayedFile(ECLIPSE_CLEAN_SRC));
            task.dependsOn("extractCleanResources");
        }

        task = makeTask("extractMcpcResources", ExtractTask.class);
        {
            task.exclude(JAVA_FILES);
            task.from(delayedFile(ZIP_RENAMED_MCPC));
            task.into(delayedFile(ECLIPSE_MCPC_RES));
            task.dependsOn("remapMcpcJar", "extractWorkspace");
            task.onlyIf(new Spec() {

                @Override
                public boolean isSatisfiedBy(Object arg0)
                {
                    File dir = delayedFile(ECLIPSE_MCPC_RES).call();
                    if (!dir.exists())
                        return true;
                    
                    ConfigurableFileTree tree = project.fileTree(dir);
                    tree.include("**/*.java");
                    
                    return !tree.isEmpty();
                }
                
            });
        }

        task = makeTask("extractMcpcSources", ExtractTask.class);
        {
            task.include(JAVA_FILES);
            task.from(delayedFile(ZIP_RENAMED_MCPC));
            task.into(delayedFile(ECLIPSE_MCPC_SRC));
            task.dependsOn("extractMcpcResources");
            task.onlyIf(new Spec() {

                @Override
                public boolean isSatisfiedBy(Object arg0)
                {
                    File dir = delayedFile(ECLIPSE_MCPC_SRC).call();
                    if (!dir.exists())
                        return true;
                    
                    ConfigurableFileTree tree = project.fileTree(dir);
                    tree.include("**/*.java");
                    
                    return !tree.isEmpty();
                }
                
            });
        }
    }
    
    @SuppressWarnings("serial")
    private void createProjectTasks()
    {
        FMLVersionPropTask sub = makeTask("createVersionPropertiesFML", FMLVersionPropTask.class);
        {
            //sub.setTasks("createVersionProperties");
            //sub.setBuildFile(delayedFile("{FML_DIR}/build.gradle"));
            sub.setVersion(new Closure<String>(project)
            {
                @Override
                public String call(Object... args)
                {
                    return FmlDevPlugin.getVersionFromGit(project, new File(delayedString("{FML_DIR}").call()));
                }
            });
            sub.setOutputFile(delayedFile(FML_VERSIONF));
        }
        
        ExtractTask extract = makeTask("extractRes", ExtractTask.class);
        {
            extract.into(delayedFile(EXTRACTED_RES));
            for (File f : delayedFile("src/main").call().listFiles())
            {
                if (f.isDirectory())
                    continue;
                String path = f.getAbsolutePath();
                if (path.endsWith(".jar") || path.endsWith(".zip"))
                    extract.from(delayedFile(path));
            }
        }

        GenDevProjectsTask task = makeTask("generateProjectClean", GenDevProjectsTask.class);
        {
            task.setTargetDir(delayedFile(ECLIPSE_CLEAN));
            task.setJson(delayedFile(MCPC_JSON_DEV)); // Change to FmlConstants.JSON_BASE eventually, so that it's the base vanilla json
            
            task.addSource(delayedFile(ECLIPSE_CLEAN_SRC));
            
            task.addResource(delayedFile(ECLIPSE_CLEAN_RES));
            
            task.dependsOn("extractNatives");
        }

        task = makeTask("generateProjectMcpc", GenDevProjectsTask.class);
        {
            task.setJson(delayedFile(MCPC_JSON_DEV));
            task.setTargetDir(delayedFile(ECLIPSE_MCPC));

            task.addSource(delayedFile(ECLIPSE_MCPC_SRC));
            task.addSource(delayedFile(MCPC_SOURCES));
            task.addTestSource(delayedFile(MCPC_TEST_SOURCES));

            task.addResource(delayedFile(ECLIPSE_MCPC_RES));
            task.addResource(delayedFile(MCPC_RESOURCES));
            task.addResource(delayedFile(EXTRACTED_RES));
            task.addTestSource(delayedFile(MCPC_TEST_SOURCES));

            task.dependsOn("extractRes", "extractNatives","createVersionPropertiesFML");
        }

        makeTask("generateProjects").dependsOn("generateProjectClean", "generateProjectMcpc");
    }
    
    private void createEclipseTasks()
    {
        SubprojectTask task = makeTask("eclipseClean", SubprojectTask.class);
        {
            task.setBuildFile(delayedFile(ECLIPSE_CLEAN + "/build.gradle"));
            task.setTasks("eclipse");
            task.dependsOn("extractCleanSource", "generateProjects");
        }

        task = makeTask("eclipseMcpc", SubprojectTask.class);
        {
            task.setBuildFile(delayedFile(ECLIPSE_MCPC + "/build.gradle"));
            task.setTasks("eclipse");
            task.dependsOn("extractMcpcSources", "generateProjects");
        }

        makeTask("eclipse").dependsOn("eclipseClean", "eclipseMcpc");
    }
    
    @SuppressWarnings("unused")
    private void createMiscTasks()
    {
        DelayedFile rangeMapClean = delayedFile("{BUILD_DIR}/tmp/rangemapCLEAN.txt");
        DelayedFile rangeMapDirty = delayedFile("{BUILD_DIR}/tmp/rangemapDIRTY.txt");
        
        ExtractS2SRangeTask extractRange = makeTask("extractRangeMcpc", ExtractS2SRangeTask.class);
        {
            extractRange.setLibsFromProject(delayedFile(ECLIPSE_MCPC + "/build.gradle"), "compile", true);
            extractRange.addIn(delayedFile(ECLIPSE_MCPC_SRC));
            extractRange.setRangeMap(rangeMapDirty);
        }
        
        ApplyS2STask applyS2S = makeTask("retroMapMcpc", ApplyS2STask.class);
        {
            applyS2S.addIn(delayedFile(ECLIPSE_MCPC_SRC));
            applyS2S.setOut(delayedFile(PATCH_DIRTY));
            applyS2S.addSrg(delayedFile(MCP_2_SRG_SRG));
            applyS2S.addExc(delayedFile(MCP_EXC));
            applyS2S.addExc(delayedFile(SRG_EXC)); // just in case
            applyS2S.setRangeMap(rangeMapDirty);
            applyS2S.dependsOn("genSrgs", extractRange);
        }
        
        extractRange = makeTask("extractRangeClean", ExtractS2SRangeTask.class);
        {
            extractRange.setLibsFromProject(delayedFile(ECLIPSE_CLEAN + "/build.gradle"), "compile", true);
            extractRange.addIn(delayedFile(REMAPPED_CLEAN));
            extractRange.setRangeMap(rangeMapClean);
        }
        
        applyS2S = makeTask("retroMapClean", ApplyS2STask.class);
        {
            applyS2S.addIn(delayedFile(REMAPPED_CLEAN));
            applyS2S.setOut(delayedFile(PATCH_CLEAN));
            applyS2S.addSrg(delayedFile(MCP_2_SRG_SRG));
            applyS2S.addExc(delayedFile(MCP_EXC));
            applyS2S.addExc(delayedFile(SRG_EXC)); // just in case
            applyS2S.setRangeMap(rangeMapClean);
            applyS2S.dependsOn("genSrgs", extractRange);
        }
        
        GeneratePatches task2 = makeTask("genPatches", GeneratePatches.class);
        {
            task2.setPatchDir(delayedFile(MCPC_PATCH_DIR));
            task2.setOriginal(delayedFile(ECLIPSE_CLEAN_SRC));
            task2.setChanged(delayedFile(ECLIPSE_MCPC_SRC));
            task2.setOriginalPrefix("../src-base/minecraft");
            task2.setChangedPrefix("../src-work/minecraft");
            task2.getTaskDependencies().getDependencies(task2).clear(); // remove all the old dependants.
            task2.setGroup("MCPC");
        }
        
        if (false) // COMMENT OUT SRG PATCHES!
        {
            task2.setPatchDir(delayedFile(MCPC_PATCH_DIR));
            task2.setOriginal(delayedFile(PATCH_CLEAN)); // was ECLIPSE_CLEAN_SRC
            task2.setChanged(delayedFile(PATCH_DIRTY)); // ECLIPSE_FORGE_SRC
            task2.setOriginalPrefix("../src-base/minecraft");
            task2.setChangedPrefix("../src-work/minecraft");
            task2.dependsOn("retroMapMcpc", "retroMapClean");
            task2.setGroup("MCPC");
        }

        Delete clean = makeTask("cleanMcpc", Delete.class);
        {
            clean.delete("eclipse");
            clean.setGroup("Clean");
        }
        project.getTasks().getByName("clean").dependsOn("cleanMcpc");

        ObfuscateTask obf = makeTask("obfuscateJar", ObfuscateTask.class);
        {
            obf.setSrg(delayedFile(MCP_2_NOTCH_SRG));
            obf.setExc(delayedFile(JOINED_EXC));
            obf.setReverse(false);
            obf.setPreFFJar(delayedFile(JAR_SRG_MCPC));
            obf.setOutJar(delayedFile(REOBF_TMP));
            obf.setBuildFile(delayedFile(ECLIPSE_MCPC + "/build.gradle"));
            obf.setMethodsCsv(delayedFile(METHODS_CSV));
            obf.setFieldsCsv(delayedFile(FIELDS_CSV));
            obf.dependsOn("genSrgs");
        }

        GenBinaryPatches task3 = makeTask("genBinPatches", GenBinaryPatches.class);
        {
            task3.setCleanClient(delayedFile(Constants.JAR_CLIENT_FRESH));
            task3.setCleanServer(delayedFile(Constants.JAR_SERVER_FRESH));
            task3.setCleanMerged(delayedFile(Constants.JAR_MERGED));
            task3.setDirtyJar(delayedFile(REOBF_TMP));
            task3.setDeobfDataLzma(delayedFile(DEOBF_DATA));
            task3.setOutJar(delayedFile(BINPATCH_TMP));
            task3.setSrg(delayedFile(JOINED_SRG));
            task3.addPatchList(delayedFileTree(MCPC_PATCH_DIR));
            task3.addPatchList(delayedFileTree(FORGE_PATCH_DIR));
            task3.addPatchList(delayedFileTree(FML_PATCH_DIR));
            task3.dependsOn("obfuscateJar", "compressDeobfData");
        }

        /*
        ForgeVersionReplaceTask task4 = makeTask("ciWriteBuildNumber", ForgeVersionReplaceTask.class);
        {
            task4.getOutputs().upToDateWhen(Constants.CALL_FALSE);
            task4.setOutputFile(delayedFile(FORGE_VERSION_JAVA));
            task4.setReplacement(delayedString("{BUILD_NUM}"));
        }

        SubmoduleChangelogTask task5 = makeTask("fmlChangelog", SubmoduleChangelogTask.class);
        {
            task5.setSubmodule(delayedFile("fml"));
            task5.setModuleName("FML");
            task5.setPrefix("MinecraftForge/FML");
        }
        */
    }
    
    @SuppressWarnings("serial")
    private void createPackageTasks()
    {
        /*
        ChangelogTask log = makeTask("createChangelog", ChangelogTask.class);
        {
            log.getOutputs().upToDateWhen(Constants.CALL_FALSE);
            log.setServerRoot(delayedString("{JENKINS_SERVER}"));
            log.setJobName(delayedString("{JENKINS_JOB}"));
            log.setAuthName(delayedString("{JENKINS_AUTH_NAME}"));
            log.setAuthPassword(delayedString("{JENKINS_AUTH_PASSWORD}"));
            log.setTargetBuild(delayedString("{BUILD_NUM}"));
            log.setOutput(delayedFile(CHANGELOG));
        }
        

        VersionJsonTask vjson = makeTask("generateVersionJson", VersionJsonTask.class);
        {
            vjson.setInput(delayedFile(INSTALL_PROFILE));
            vjson.setOutput(delayedFile(VERSION_JSON));
            vjson.dependsOn("generateInstallJson");
        }
        */

        final DelayedJar uni = makeTask("packageUniversal", DelayedJar.class);
        {
            uni.setClassifier(delayedString("B{BUILD_NUM}").call());
            uni.getInputs().file(delayedFile(MCPC_JSON_REL));
            uni.getOutputs().upToDateWhen(Constants.CALL_FALSE);
            uni.from(delayedZipTree(BINPATCH_TMP));
            uni.from(delayedFileTree(FML_RESOURCES));
            uni.from(delayedFileTree(FORGE_RESOURCES));
            uni.from(delayedFileTree(MCPC_RESOURCES));
            uni.from(delayedFileTree(EXTRACTED_RES));
            uni.from(delayedFile(FML_VERSIONF));
            uni.from(delayedFile(FML_LICENSE));
            uni.from(delayedFile(FML_CREDITS));
            uni.from(delayedFile(FORGE_LICENSE));
            uni.from(delayedFile(FORGE_CREDITS));
            uni.from(delayedFile(PAULSCODE_LISCENCE1));
            uni.from(delayedFile(PAULSCODE_LISCENCE2));
            uni.from(delayedFile(DEOBF_DATA));
            uni.from(delayedFile(CHANGELOG));
            uni.from(delayedFile(VERSION_JSON));
            uni.exclude("devbinpatches.pack.lzma");
            uni.setDuplicatesStrategy(DuplicatesStrategy.EXCLUDE);
            uni.setIncludeEmptyDirs(false);
            uni.setManifest(new Closure<Object>(project)
            {
                public Object call()
                {
                    Manifest mani = (Manifest) getDelegate();
                    mani.getAttributes().put("Main-Class", delayedString("{MAIN_CLASS}").call());
                    mani.getAttributes().put("TweakClass", delayedString("{FML_TWEAK_CLASS}").call());
                    mani.getAttributes().put("Class-Path", getServerClassPath(delayedFile(MCPC_JSON_REL).call()));
                    return null;
                }
            });
            
//            uni.doLast(new Action<Task>()
//            {
//                @Override
//                public void execute(Task arg0)
//                {
//                    try
//                    {
//                        signJar(((DelayedJar)arg0).getArchivePath(), "forge", "*/*/**", "!paulscode/**");
//                    }
//                    catch (Exception e)
//                    {
//                        Throwables.propagate(e);
//                    }
//                }
//            });
            
            uni.setDestinationDir(delayedFile("{BUILD_DIR}/distributions").call());
            //uni.dependsOn("genBinPatches", "createChangelog", "createVersionPropertiesFML", "generateVersionJson");
            uni.dependsOn("genBinPatches", "createVersionPropertiesFML");
        }
        project.getArtifacts().add("archives", uni);

        FileFilterTask task = makeTask("generateInstallJson", FileFilterTask.class);
        {
            task.setInputFile(delayedFile(MCPC_JSON_REL));
            task.setOutputFile(delayedFile(INSTALL_PROFILE));
            task.addReplacement("@minecraft_version@", delayedString("{MC_VERSION}"));
            task.addReplacement("@version@", delayedString("{VERSION}"));
            task.addReplacement("@project@", delayedString("mcpc-plus"));
            task.addReplacement("@artifact@", delayedString("net.minecraftforge:forge:{MC_VERSION}-{VERSION}"));
            task.addReplacement("@universal_jar@", new Closure<String>(project)
            {
                public String call()
                {
                    return uni.getArchiveName();
                }
            });
            task.addReplacement("@timestamp@", new Closure<String>(project)
            {
                public String call()
                {
                    return (new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ")).format(new Date());
                }
            });
        }

        Zip inst = makeTask("packageInstaller", Zip.class);
        {
            inst.setClassifier("installer");
            inst.from(new Closure<File>(project) {
                public File call()
                {
                    return uni.getArchivePath();
                }
            });
            inst.from(delayedFile(INSTALL_PROFILE));
            inst.from(delayedFile(CHANGELOG));
            inst.from(delayedFile(FML_LICENSE));
            inst.from(delayedFile(FML_CREDITS));
            inst.from(delayedFile(FORGE_LICENSE));
            inst.from(delayedFile(FORGE_CREDITS));
            inst.from(delayedFile(PAULSCODE_LISCENCE1));
            inst.from(delayedFile(PAULSCODE_LISCENCE2));
            inst.from(delayedFile(FORGE_LOGO));
            inst.from(delayedZipTree(INSTALLER_BASE), new CopyInto("", "!*.json", "!*.png"));
            inst.dependsOn("packageUniversal", "downloadBaseInstaller", "generateInstallJson");
            inst.rename("forge_logo\\.png", "big_logo.png");
            inst.setExtension("jar");
        }
        project.getArtifacts().add("archives", inst);
    }
    
    @Override
    public void afterEvaluate()
    {
        super.afterEvaluate();
        
        SubprojectTask task = (SubprojectTask) project.getTasks().getByName("eclipseClean");
        task.configureProject(getExtension().getSubprojects());
        task.configureProject(getExtension().getCleanProject());
        
        task = (SubprojectTask) project.getTasks().getByName("eclipseMcpc");
        task.configureProject(getExtension().getSubprojects());
        task.configureProject(getExtension().getCleanProject());
    }
}
