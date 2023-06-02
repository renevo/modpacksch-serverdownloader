package main

import (
	"encoding/json"
	"log"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"time"

	"github.com/cavaliergopher/grab/v3"
)

// this is additions that i made so i can create a pack and then install it on servers easier without publishing it to the internet.
// it does not currently support curseforge packs, as that is yet another copy/paste that would need to be done, and i don't have an API key so...
//
// Below is an example localpack with the minimum amount of fields required placed in ./test-localpack/modpack.json
//
/*
{
    "files": [
        {
            "path": "./",
            "url": "https://raw.githubusercontent.com/renevo/ioc/main/README.md",
            "name": "README.md",
            "sha1": "a8f125b9b1cbef1e3df61e363463e4d16823d92a",
            "clientonly": false
        }
    ],
    "targets": [
        {
            "version": "43.2.13",
            "name": "forge",
            "type": "modloader"
        },
        {
            "version": "1.19.2",
            "name": "minecraft",
            "type": "game"
        },
        {
            "version": "17.0.7+7",
            "name": "java",
            "type": "runtime"
        }
    ],
    "name": "1.0.0",
    "type": "beta"
}
*/
// You would then run go run . --path ./test-localpack --localpack ./test-localpack/modpack.json --integrity --integrityupdate

func HandleLocalPack(file, installPath string) {
	if installPath == "" {
		installPath = "./"
	}

	if err := os.MkdirAll(installPath, os.ModePerm); err != nil {
		log.Fatalf("Error creating install path: %v", err)
	}

	versionInfo := &VersionInfo{}
	localPackData, err := os.ReadFile(file)
	if err != nil {
		log.Fatalf("Error reading modpack %q: %v", file, err)
	}

	if err := json.Unmarshal(localPackData, versionInfo); err != nil {
		log.Fatalf("Error parsing modpack: %v", err)
	}

	upgrade := false
	if _, err := os.Stat(filepath.Join(installPath, "version.json")); !os.IsNotExist(err) {
		upgrade = true
	}

	upgradeStr := ""

	if upgrade {
		upgradeStr = " as an update"
	}

	if !QuestionYN(true, "Continuing will install %s version %s%s. Do you wish to continue?", file, versionInfo.Name, upgradeStr) {
		log.Fatalf("Aborted by user")
	}

	// probably should be a separate function
	if upgrade {
		err, info := GetVersionInfoFromFile(filepath.Join(installPath, "version.json"))
		if err != nil {
			if !QuestionYN(true, "An error occurred whilst trying to read the previous installation at %s: %v\nWould you like to continue anyway? You should probably delete folders with mods and configs in it, first!", installPath, err) {
				log.Fatalf("Aborting due to corrupted previous installation")
			} else {
				// TODO: handle removing folders here
			}
		}

		oldDownloads := info.GetDownloads()
		getSortFunc := func(arr []Download) func(i int, j int) bool {
			return func(i int, j int) bool {
				return arr[i].FullPath < arr[j].FullPath
			}
		}

		sort.SliceStable(oldDownloads, getSortFunc(oldDownloads))
		sort.SliceStable(downloads, getSortFunc(downloads))

		lastFound := -1

		downloadsLen := len(downloads)

		var changedFilesOld []Download
		var changedFilesNew []Download

		var newFiles []Download
		var oldDeletedFiles []Download
		var integrityFailures []Download

		mcCleanup(installPath)

		for _, oldDown := range oldDownloads {
			for i := lastFound + 1; i < downloadsLen; i++ {
				newDown := downloads[i]
				if oldDown.FullPath == newDown.FullPath {
					lastFound = i
					if oldDown.HashType != newDown.HashType || oldDown.Hash != newDown.Hash {
						changedFilesOld = append(changedFilesOld, oldDown)
						changedFilesNew = append(changedFilesNew, newDown)
						LogIfVerbose("Found changed file %s\n", newDown.FullPath)
					} else if Options.Integrity {
						LogIfVerbose("Checking integrity of file %s\n", newDown.FullPath)
						if !newDown.VerifyChecksum(installPath) {
							integrityFailures = append(integrityFailures, oldDown)
						}
					}
					break
				}
				if newDown.FullPath > oldDown.FullPath {
					lastFound = i - 1
					oldDeletedFiles = append(oldDeletedFiles, oldDown)
					LogIfVerbose("Found deleted file %s\n", newDown.FullPath)
					break
				}
				newFiles = append(newFiles, newDown)
				LogIfVerbose("Found new file %s\n", newDown.FullPath)
			}
		}

		log.Printf("This install has %v files changed, %v new files and %v deleted files\n", len(changedFilesOld), len(newFiles), len(oldDeletedFiles))

		var failedChecksums []Download

		for _, oldDown := range changedFilesOld {
			if !oldDown.VerifyChecksum(installPath) {
				failedChecksums = append(failedChecksums, oldDown)
				LogIfVerbose("Detected failed checksum on %s\n", oldDown.FullPath)
			}
		}

		if len(failedChecksums) > 0 {
			overwrite := QuestionYN(Options.Integrityupdate || Options.Integrity, "There are %v failed checksums on files to be updated. This may be as a result of manual config changes. Do you wish to overwrite them with the files from the update?", failedChecksums)
			if overwrite {
				for i := range failedChecksums {
					changedFilesNew[i] = changedFilesNew[len(changedFilesNew)-1]
				}
				changedFilesNew = changedFilesNew[:len(changedFilesNew)-len(failedChecksums)]
			}
		}

		if len(integrityFailures) > 0 {
			overwrite := QuestionYN(true, "There are %v failed checksums on already existing files. This may be as a result of manual config changes. Do you wish to overwrite them with the files from the update?", failedChecksums)
			if overwrite {
				changedFilesNew = append(changedFilesNew, integrityFailures...)
			}
		}

		downloads = append(changedFilesNew, newFiles...)

		log.Println("Deleting removed files...")
		for _, down := range oldDeletedFiles {
			filePath := filepath.Join(installPath, down.FullPath)
			LogIfVerbose("Removing %s\n", filePath)
			if os.Remove(filePath) != nil {
				log.Println("Error occurred whilst removing file " + filePath)
				continue
			}
			tempPath := filepath.Join(installPath, down.Path)
			dir, err := os.Open(tempPath)
			empty := false
			if err == nil {
				empty = true
				names, _ := dir.Readdirnames(-1)
				for _, name := range names {
					if name != "." && name != ".." {
						empty = false
						break
					}
				}
			}
			if empty {
				LogIfVerbose("Removing %s as is empty\n", tempPath)
				if os.RemoveAll(tempPath) != nil {
					log.Println("Error occurred whilst removing folder " + tempPath)
				}
			}
		}

		log.Println("Performing update...")
	} else {
		log.Println("Performing installation...")
	}

	downloads = versionInfo.GetDownloads()

	err, ml := versionInfo.GetModLoader()
	if err != nil {
		log.Fatalf("Error getting Modloader: %v", err)
	}

	modLoaderDls := ml.GetDownloads(installPath)

	URL, _ := url.Parse("https://media.forgecdn.net/files/3557/251/Log4jPatcher-1.0.0.jar")
	downloads = append(downloads, Download{"log4jfix/", *URL, "Log4jPatcher-1.0.0.jar", "sha1", "eb20584e179dc17b84b6b23fbda45485cd4ad7cc", filepath.Join("log4jfix/", "Log4jPatcher-1.0.0.jar")})
	downloads = append(downloads, modLoaderDls...)

	var java JavaProvider
	if Options.Nojava {
		java = &NoOpJavaProvider{}
	} else {
		java = versionInfo.GetJavaProvider()
	}

	downloads = append(downloads, java.GetDownloads(installPath)...)

	for _, download := range downloads {
		log.Println(download.FullPath)
	}

	grabs, err := GetBatch(Options.Threads, installPath, downloads...)
	if err != nil {
		log.Fatal(err)
	}
	responses := make([]*grab.Response, 0, len(downloads))
	t := time.NewTicker(200 * time.Millisecond)
	defer t.Stop()

Loop:
	for {
		select {
		case resp := <-grabs:
			if resp != nil {
				// a new response has been received and has started downloading
				responses = append(responses, resp)
			} else {
				// channel is closed - all downloads are complete
				updateUI(responses)
				break Loop
			}

		case <-t.C:
			// update UI every 200ms
			updateUI(responses)
		}
	}

	log.Printf(
		"Downloaded %d successful, %d failed, %d incomplete.\n",
		succeeded,
		failed,
		inProgress,
	)

	if failed > 0 {
		if !QuestionYN(true, "Some downloads failed. Would you like to continue anyway?") {
			os.Exit(failed)
		}
	}

	java.Install(installPath)

	time.Sleep(time.Second * 2)

	ml.Install(installPath, java)

	if err := os.WriteFile(filepath.Join(installPath, "version.json"), localPackData, os.ModePerm); err != nil {
		log.Fatalf("Failed to output version file")
	}

	versionInfo.WriteStartScript(installPath, ml, java)

	log.Printf("Installed!")
}
