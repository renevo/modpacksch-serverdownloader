package net.creeperhost.ftbserverdownloader;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.creeperhost.creeperlauncher.CreeperLogger;
import net.creeperhost.creeperlauncher.api.DownloadableFile;
import net.creeperhost.creeperlauncher.util.FileUtils;
import net.creeperhost.creeperlauncher.util.MiscUtils;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

public class ServerVersion {
    public long id;
    public long pack;
    public String name;
    public String type;
    public String Modloader;
    public String ModloaderType;
    public long minimumRam;
    public long recommendRam;
    public String Vanilla;
    private ArrayList<CompletableFuture> futures = new ArrayList<>();
    private ArrayList<DownloadableFile> files = new ArrayList<DownloadableFile>();
    ServerVersion(long pack, long id)
    {
        this.pack = pack;
        this.id = id;
    }
    public void downloadManifest()
    {
        HttpClient wclient = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.modpacks.ch/public/modpack/"+this.pack+"/"+this.id))
                .build();
        wclient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body)
                .thenAccept((String data) -> {
                    Gson gson = new Gson();
                    JsonObject apiresp = gson.fromJson(data, JsonObject.class);
                    JsonArray files = apiresp.getAsJsonArray("files");
                    JsonArray targets = apiresp.getAsJsonArray("targets");
                    this.name = apiresp.get("name").getAsString();
                    this.type = apiresp.get("type").getAsString();
                    JsonObject specs = apiresp.getAsJsonObject("specs");
                    this.minimumRam = specs.get("minimum").getAsLong();
                    this.recommendRam = specs.get("recommended").getAsLong();
                    for(JsonElement target : targets)
                    {
                        JsonObject tar = target.getAsJsonObject();
                        if(tar.get("type").getAsString().equals("game"))
                        {
                            this.Vanilla = tar.get("version").getAsString();
                        }
                        if(tar.get("type").getAsString().equals("modloader"))
                        {
                            this.Modloader = tar.get("version").getAsString();
                            this.ModloaderType = tar.get("name").getAsString();
                        }
                    }

                    for (JsonElement file : files) {
                        JsonObject fileInfo = file.getAsJsonObject();
                        DownloadableFile downloadableFile = gson.fromJson(file, DownloadableFile.class);
                        this.files.add(downloadableFile);
                    }
                }).join();
        CompletableFuture<Void> combinedFuture = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0])).exceptionally((t) ->
                {
                    t.printStackTrace();
                    return null;
                }
        );
        combinedFuture.join();
    }
    public void install()
    {
        ArrayList<CompletableFuture<Void>> futures = new ArrayList<>();

        boolean modloaderDownloading = false;

        String installerFileName = "";

        if (this.ModloaderType.equals("forge")) {
            URI forgeURI = MiscUtils.findForgeDownloadURL(this.Vanilla, this.Modloader);
            if (forgeURI != null) {
                try {
                    String fileName = forgeURI.toString().substring(forgeURI.toString().lastIndexOf('/') + 1);
                    DownloadableFile downloadableFile = new DownloadableFile(this.Modloader, "./", forgeURI.toString(), null, -1, false, false, -1, fileName, "modloader", "0");
                    // TODO: Actual checksum and sizes
                    installerFileName = downloadableFile.getName();
                    files.add(downloadableFile);
                    modloaderDownloading = true;
                } catch (Exception e) {

                }
            }
        }
        //Here for if needed...
        /*File mods = new File(Main.installPath.toFile(), "mods/");
        File coremods = new File(Main.installPath.toFile(), "coremods/");
        File instmods = new File(Main.installPath.toFile(), "instmods/");

        File config = new File(Main.installPath.toFile(), "config/");
        File resources = new File(Main.installPath.toFile(), "resources/");
        File scripts = new File(Main.installPath.toFile(), "scripts/");

        FileUtils.deleteDirectory(mods);
        FileUtils.deleteDirectory(coremods);
        FileUtils.deleteDirectory(instmods);
        FileUtils.deleteDirectory(config);
        FileUtils.deleteDirectory(resources);
        FileUtils.deleteDirectory(scripts);*/


        ArrayList<String> directories = new ArrayList<String>();
        for(DownloadableFile downloadableFile : files) {
            if (!downloadableFile.getClientOnly()) {
                if(!directories.contains(downloadableFile.getPath()))
                {
                    if(downloadableFile.getPath().length() > 1) {
                        directories.add(downloadableFile.getPath());
                        File dr = Main.installPath.resolve(downloadableFile.getPath()).toFile();
                        if(dr.exists()) {
                           FileUtils.deleteDirectory(dr);
                        }
                    }
                }
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        downloadableFile.prepare();
                        downloadableFile.download(Main.installPath.resolve(downloadableFile.getPath()).resolve(downloadableFile.getName()), true, false);
                    } catch (Throwable throwable) {
                        System.out.println("[" + Main.dlnum.get() + "/" + files.size() + "] Unable to download: " + throwable.getMessage());
                        throwable.printStackTrace();
                    }
                }).thenRunAsync(() -> {
                    System.out.println("[" + Main.dlnum.incrementAndGet() + "/" + files.size() + "] Downloaded '" + downloadableFile.getName() + "' to '" + downloadableFile.getPath() + "' [" + downloadableFile.getSize() + " bytes]...");
                }));
            }
        }

        CompletableFuture<Void> combinedFuture = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0])).exceptionally((t) ->
                {
                    t.printStackTrace();
                    return null;
                }
        );
        combinedFuture.join();

        System.out.println("["+Main.dlnum.get()+"/"+files.size()+"] Finished.");

        if (ModloaderType.equals("forge")) {
            if (modloaderDownloading)
            {
                ProcessBuilder processBuilder = new ProcessBuilder().command("java", "-jar", installerFileName, "--installServer", Main.installPath.toAbsolutePath().toString());
                processBuilder.inheritIO();
                boolean error = false;
                try {
                    System.out.println("Invoking forge " + Modloader + " installer.");
                    System.out.println("================= FORGE INSTALL BEGINS =================");
                    Process start = processBuilder.start();
                    start.waitFor();
                } catch (IOException | InterruptedException e) {
                    error = true;
                }
                if(!(Main.installPath.resolveSibling("forge-" + Vanilla + "-" + Modloader + ".jar").toFile().exists() || Main.installPath.resolveSibling("forge-" + Vanilla + "-" + Modloader + "-universal.jar").toFile().exists())) {
                    error = true;
                }

                System.out.println("=================  FORGE INSTALL ENDS  =================");

                if (error) {
                    System.out.println("An error occurred whilst installing forge " + Modloader + ". Please install manually.");
                } else {
                    Main.installPath.resolveSibling(installerFileName + ".log").toFile().delete();
                    Main.installPath.resolveSibling(installerFileName).toFile().delete();
                }
            } else {
                System.out.println("Could not find download location for forge " + Modloader + ". Please install manually.");
            }
        } else {
            System.out.println("Modloader type " + ModloaderType + " is not currently supported. Please check if an updated version of the downloader is available.");
        }
        if(Main.generateStart) {
            String forgeJar = "";
            if (Main.installPath.resolveSibling("forge-" + Vanilla + "-" + Modloader + ".jar").toFile().exists()) {
                forgeJar = "forge-" + Vanilla + "-" + Modloader + ".jar";
            }
            if (Main.installPath.resolveSibling("forge-" + Vanilla + "-" + Modloader + "-universal.jar").toFile().exists()) {
                forgeJar = "forge-" + Vanilla + "-" + Modloader + "-universal.jar";
            }
            String startCmd = "-server -XX:+UseG1GC -XX:+UnlockExperimentalVMOptions -Xmx" + this.recommendRam + "M -Xms" + this.minimumRam + "M -jar "+forgeJar+" nogui";
            File bash = new File("start.sh");
            try {
                if (bash.createNewFile()) {
                    String bashFile = "#!/bin/bash\necho \"Do you agree to the Mojang EULA available at https://account.mojang.com/documents/minecraft_eula ?\"\n$EULA=${read  -n 1 -p \"[y/n]\"}\nif [ \"$EULA\" = \"y\" ]; then\n    echo \"eula=true\" > eula.txt\nfi\njava "+startCmd;
                    FileWriter bashWriter = new FileWriter(bash.toPath().toAbsolutePath().toString());
                    bashWriter.write(bashFile);
                    bashWriter.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            File batch = new File("start.bat");
            try {
                if (batch.createNewFile()) {
                    String batchFile = "@echo off\r\necho \"Do you agree to the Mojang EULA available at https://account.mojang.com/documents/minecraft_eula ?\"\r\nset /p EULA=[y/n]\r\nIF /I \"%EULA%\" NEQ \"y\" GOTO END\r\necho eula=true>eula.txt\r\n:END\r\njava.exe "+startCmd;
                    FileWriter batchWriter = new FileWriter(batch.toPath().toAbsolutePath().toString());
                    batchWriter.write(batchFile);
                    batchWriter.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        System.out.println("Pack install Finished.");
    }
}
