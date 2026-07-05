;; Read two channels at once with a multi reader, aligned on their common
;; domain, and print the aligned rows with wall-clock timestamps.

(import '(com.opendaq Instance Channel MultiReader Daq)
        '(java.time ZoneOffset)
        '(java.time.format DateTimeFormatter))

(def fmt (.withZone (DateTimeFormatter/ofPattern "HH:mm:ss.SSSSSS") ZoneOffset/UTC))

(def instance (Instance.))
(.addDevice instance "daqref://device0")

(def ch0 (.asType (.findComponent instance "Dev/RefDev0/IO/AI/RefCh0") Channel))
(def ch1 (.asType (.findComponent instance "Dev/RefDev0/IO/AI/RefCh1") Channel))
(.setPropertyValue ch0 "Frequency" 0.5)
(.setPropertyValue ch1 "Frequency" 2.0)

(def reader (MultiReader. [(.get (.getSignals ch0) 0)
                           (.get (.getSignals ch1) 0)]))

(when-not
  (loop [attempt 0]
    (if (>= attempt 10)
      false
      (let [result (.readWithDomain reader 8 1000)
            ticks (.domainTicks result 0)]
        (if (zero? (alength ticks))
          (recur (inc attempt))
          (let [to-timestamp (Daq/domainTimeConverter reader)]
            (printf "%-16s%14s%14s%n" "timestamp" "signal 0" "signal 1")
            (dotimes [k (alength ticks)]
              (printf "%-16s%14.6f%14.6f%n"
                      (.format fmt (.apply to-timestamp (aget ticks k)))
                      (aget (.doubleValues result 0) k)
                      (aget (.doubleValues result 1) k)))
            (flush)
            true)))))
  (println "Multi reader did not synchronise in time."))
