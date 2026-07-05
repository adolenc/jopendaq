;; Finding components with search filters.
;;
;; The getter methods for a device's tree -- getChannels, getSignals,
;; getDevices, getFunctionBlocks, and a folder's getItems -- all take an
;; optional search filter.  A filter answers two questions about every
;; component the search walks over: "accept this one into the result?" and
;; "descend into its children?".
;;
;; With no filter the getters return only the immediate, visible children, so
;; getChannels of the top-level device below finds nothing -- the channels live
;; one device deeper.  A createRecursiveSearchFilter wrapper makes the search
;; descend.  Filters compose: createAndSearchFilter, createOrSearchFilter and
;; createNotSearchFilter take other filters as their arguments.  The recursive
;; wrapper must be the outermost one -- never nest it inside another filter.

(import '(com.opendaq Instance Channel SearchFilter TagsPrivate))
(require '[clojure.string :as str])

(defn show
  "Print components by their local id under a label."
  [label components]
  (let [ids (if (seq components)
              (str/join ", " (map #(.getLocalId %) components))
              "(none)")]
    (println label)
    (println (str "    => " ids))
    (println)))

(def instance (Instance.))
(.addDevice instance "daqref://device0")
(def root (.getRootDevice instance))

;; Give a couple of channels some tags, so the tag filter has something to
;; match (RefCh0/RefCh1 come from the reference device unlabelled).
(doseq [channel (.getChannels root (SearchFilter/createRecursiveSearchFilter
                                     (SearchFilter/createAnySearchFilter)))]
  (let [tags (.asType (.getTags channel) TagsPrivate)]
    (.add tags "analog")
    (when (= (.getLocalId channel) "RefCh0")
      (.add tags "primary"))))

(show "channels, no filter (immediate children only)"
      (.getChannels root))

(show "channels, recursive(any)"
      (.getChannels root (SearchFilter/createRecursiveSearchFilter
                           (SearchFilter/createAnySearchFilter))))

(show "channels, recursive(id = RefCh1)"
      (.getChannels root (SearchFilter/createRecursiveSearchFilter
                           (SearchFilter/createLocalIdSearchFilter "RefCh1"))))

(show "signals, recursive(id = AI0 OR id = AI1)"
      (.getSignals root (SearchFilter/createRecursiveSearchFilter
                          (SearchFilter/createOrSearchFilter
                            (SearchFilter/createLocalIdSearchFilter "AI0")
                            (SearchFilter/createLocalIdSearchFilter "AI1")))))

(show "channels, recursive(NOT id = RefCh1)"
      (.getChannels root (SearchFilter/createRecursiveSearchFilter
                           (SearchFilter/createNotSearchFilter
                             (SearchFilter/createLocalIdSearchFilter "RefCh1")))))

(show "channels, recursive(tag = analog AND NOT id = RefCh1)"
      (.getChannels root (SearchFilter/createRecursiveSearchFilter
                           (SearchFilter/createAndSearchFilter
                             (SearchFilter/createRequiredTagsSearchFilter ["analog"])
                             (SearchFilter/createNotSearchFilter
                               (SearchFilter/createLocalIdSearchFilter "RefCh1"))))))
