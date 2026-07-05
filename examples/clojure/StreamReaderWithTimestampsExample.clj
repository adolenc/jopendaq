;; Read samples together with their domain ticks and convert each tick to an
;; absolute wall-clock timestamp via the signal's domain metadata.

(import '(com.opendaq Instance Channel StreamReader Daq)
        '(java.time ZoneOffset)
        '(java.time.format DateTimeFormatter))

(def fmt (.withZone (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss.SSSSSS 'UTC'")
                    ZoneOffset/UTC))

(def instance (Instance.))
(.addDevice instance "daqref://device0")

(def channel (.asType (.findComponent instance "Dev/RefDev0/IO/AI/RefCh0") Channel))
(def signal (.get (.getSignals channel) 0))
(def reader (StreamReader. signal))

(def result (.readWithDomain reader 10 2000))
(def values (.doubleValues result))
(def ticks (.domainTicks result))

(def to-timestamp (Daq/domainTimeConverter signal))
(println (str "Read " (alength values) " samples:"))
(dotimes [i (alength values)]
  (printf "  %.6f @ %s%n" (aget values i) (.format fmt (.apply to-timestamp (aget ticks i)))))
(flush)
