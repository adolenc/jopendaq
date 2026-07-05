;; Download a device's log files: enumerate them with getLogFileInfos and pull
;; each one's contents with getLog.  On a real (often remote) device the logs
;; already exist; the bundled reference device only produces one when you ask it
;; to, so this example first sets that up:
;;
;;   * give the instance a file logger sink, so openDAQ actually writes a log
;;     file;
;;   * add the device with EnableLogging = true and LoggingPath pointing at that
;;     same file -- that is the file the reference device reports and serves.
;;
;; getLogFileInfos / getLog then behave exactly as they would against a remote
;; device.

(import '(com.opendaq InstanceBuilder Instance Daq LoggerSink PropertyObject
                      Property LogFileInfo)
        '(java.nio.charset StandardCharsets)
        '(java.nio.file Files OpenOption)
        '(java.nio.file.attribute FileAttribute))

(def no-attrs (make-array FileAttribute 0))
(def work-dir (Files/createTempDirectory "java-opendaq-log-example" no-attrs))
(def device-log (.resolve work-dir "ref_device_simulator.log"))
(def downloads (Files/createDirectories (.resolve work-dir "downloads") no-attrs))

(def instance (-> (InstanceBuilder.)
                  (.setModulePath (.toString (Daq/nativeLibraryDirectory)))   ; find the bundled modules
                  (.addLoggerSink (LoggerSink. (.toString device-log)))
                  (.build)))

;; The reference device reads these two properties from its add-device config.
(def config (doto (PropertyObject.)
              (.addProperty (Property/createBoolProperty "EnableLogging" true true))
              (.addProperty (Property/createStringProperty "LoggingPath" (.toString device-log) true))))

(def device (.addDevice instance "daqref://device0" config))

;; Flush the logger so everything buffered so far is on disk before we read it.
(.flush (.getLogger (.getContext instance)))

(def infos (.getLogFileInfos device))
(if (.isEmpty infos)
  (println "Device exposes no log files.")
  (do
    (println (str "Device exposes " (.size infos) " log file(s):"))
    (println)
    (doseq [info infos]
      (let [destination (.resolve downloads (.getName info))]
        (println (str "• " (.getName info)))
        (println (str "    id:            " (.getId info)))
        (println (str "    size:          " (.getSize info) " bytes"))
        (println (str "    encoding:      " (.getEncoding info)))
        (println (str "    last-modified: " (.getLastModified info)))
        ;; size/offset let you fetch just part of a file; here, a short head preview.
        (println (str "    preview:       \""
                      (.replace (.getLog device (.getId info) 60 0) "\n" "\\n") "\""))
        (let [content (.getLog device (.getId info))]   ; whole file (defaults size -1, offset 0)
          (Files/writeString destination content StandardCharsets/UTF_8 (make-array OpenOption 0))
          (println (str "    downloaded " (.length content) " chars -> " destination))
          (println))))))
