;; Build a signal by hand and read it back with wall-clock timestamps.
;;
;; The trick for making the domain timestamps line up with the PC clock is NOT
;; to move the origin to "now": the origin stays pinned at the Unix epoch, and
;; the current time is carried entirely by the integer domain ticks.  With a
;; tick resolution of 1/1000 s (one tick = one millisecond) a sample's absolute
;; time is origin + tick/1000 s, so a tick equal to the current Unix time in
;; milliseconds reads back as the current wall-clock time.  Spacing the ticks
;; 200 apart (200 ms) then yields exactly 5 samples per second.

(import '(com.opendaq Instance DataDescriptorBuilder SampleType Unit DataRule
                      SignalConfig StreamReader ReadMode ReadTimeoutType DataPacket
                      RatioValue Daq)
        '(java.time Instant ZoneOffset)
        '(java.time.format DateTimeFormatter))

(def samples-per-second 5)
(def tick-resolution (RatioValue. 1 1000))               ; seconds per tick (1 ms)
(def ticks-per-sample (quot 1000 samples-per-second))    ; 200 ticks = 200 ms
(def fmt (.withZone (DateTimeFormatter/ofPattern "HH:mm:ss.SSSSSS") ZoneOffset/UTC))

(def instance (Instance.))

(def domain-descriptor
  (.build (doto (DataDescriptorBuilder.)
            (.setSampleType SampleType/INT64)
            (.setName "time")
            ;; Origin stays at the epoch; the wall-clock time lives in the ticks.
            (.setOrigin "1970-01-01T00:00:00Z")
            (.setTickResolution tick-resolution)
            (.setUnit (Unit. -1 "s" "second" "time"))
            (.setRule (DataRule/createLinearDataRule ticks-per-sample 0)))))

(def value-descriptor
  (.build (doto (DataDescriptorBuilder.)
            (.setSampleType SampleType/FLOAT64)
            (.setName "values"))))

(def domain-signal (doto (SignalConfig. (.getContext instance) nil "time" nil)
                     (.setDescriptor domain-descriptor)))
(def signal (doto (SignalConfig. (.getContext instance) nil "values" nil)
              (.setDescriptor value-descriptor)
              (.setDomainSignal domain-signal)))

(def reader (StreamReader. signal SampleType/FLOAT64 SampleType/INT64
                           ReadMode/SCALED ReadTimeoutType/ANY false))

(defn send-chunk
  "Send samples as one packet whose implicit domain ticks start at `offset` and
   advance by ticks-per-sample per sample."
  [offset samples]
  (let [domain-packet (DataPacket. domain-descriptor (alength samples) offset)
        packet (DataPacket/createDataPacketWithDomain
                 domain-packet value-descriptor (alength samples) 0)]
    (.setData packet samples)
    (.sendPacket signal packet)))

;; Stream a few seconds of a 5 Hz signal in real time.  Each one-second batch
;; is one packet of 5 samples; the ticks stay contiguous across batches (batch k
;; starts at startTick + k*1000 ms), so the whole run is an unbroken 5 Hz stream
;; anchored to when the program started.
(def batches 3)
(def start-tick (.toEpochMilli (Instant/now)))
(def to-timestamp (Daq/domainTimeConverter signal))
(println (str "Streaming " samples-per-second " samples/second, starting at "
              (.format fmt (.apply to-timestamp start-tick))))

(dotimes [batch batches]
  (let [base-sample (* batch samples-per-second)
        offset (+ start-tick (* base-sample ticks-per-sample))
        samples (double-array samples-per-second)]
    (dotimes [i (alength samples)]
      (aset samples i (Math/sin (* 2 Math/PI (/ (+ base-sample i) 25.0)))))
    (send-chunk offset samples)
    (let [read (.readWithDomain reader 100 1000)
          values (.doubleValues read)
          ticks (.domainTicks read)]
      (dotimes [i (alength values)]
        (printf "  %s  ->  %.4f%n"
                (.format fmt (.apply to-timestamp (aget ticks i)))
                (aget values i)))
      (flush)))
  (Thread/sleep 1000))
