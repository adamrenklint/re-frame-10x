(ns day8.re-frame.trace
  (:require [day8.re-frame.trace.panels.subvis :as subvis]
            [day8.re-frame.trace.panels.app-db :as app-db]
            [day8.re-frame.trace.styles :as styles]
            [day8.re-frame.trace.components.components :as components]
            [day8.re-frame.trace.utils.localstorage :as localstorage]
            [day8.re-frame.trace.panels.traces :as traces]
            [re-frame.trace :as trace :include-macros true]
            [re-frame.db :as db]
            [cljs.pprint :as pprint]
            [clojure.string :as str]
            [clojure.set :as set]
            [reagent.core :as r]
            [reagent.interop :refer-macros [$ $!]]
            [reagent.impl.util :as util]
            [reagent.impl.component :as component]
            [reagent.impl.batching :as batch]
            [reagent.ratom :as ratom]
            [goog.object :as gob]
            [re-frame.interop :as interop]

            [devtools.formatters.core :as devtools]))


;; from https://github.com/reagent-project/reagent/blob/3fd0f1b1d8f43dbf169d136f0f905030d7e093bd/src/reagent/impl/component.cljs#L274
(defn fiber-component-path [fiber]
  (let [name (some-> fiber
                     ($ :type)
                     ($ :displayName))
        parent (some-> fiber
                       ($ :return))
        path (some-> parent
                     fiber-component-path
                     (str " > "))
        res (str path name)]
    (when-not (empty? res) res)))

(defn component-path [c]
  ;; Alternative branch for React 16
  (if-let [fiber (some-> c ($ :_reactInternalFiber))]
    (fiber-component-path fiber)
    (component/component-path c)))

(defn comp-name [c]
  (let [n (or (component-path c)
              (some-> c .-constructor util/fun-name))]
    (if-not (empty? n)
      n
      "")))

(def static-fns
  {:render
   (fn render []
     (this-as c
       (trace/with-trace {:op-type   :render
                          :tags      {:component-path (component-path c)}
                          :operation (last (str/split (component-path c) #" > "))}
                         (if util/*non-reactive*
                           (reagent.impl.component/do-render c)
                           (let [rat        ($ c :cljsRatom)
                                 _          (batch/mark-rendered c)
                                 res        (if (nil? rat)
                                              (ratom/run-in-reaction #(reagent.impl.component/do-render c) c "cljsRatom"
                                                                     batch/queue-render reagent.impl.component/rat-opts)
                                              (._run rat false))
                                 cljs-ratom ($ c :cljsRatom)] ;; actually a reaction
                             (trace/merge-trace!
                               {:tags {:reaction      (interop/reagent-id cljs-ratom)
                                       :input-signals (when cljs-ratom
                                                        (map interop/reagent-id (gob/get cljs-ratom "watching" :none)))}})
                             res)))))})


(defn monkey-patch-reagent []
  (let [#_#_real-renderer reagent.impl.component/do-render
        real-custom-wrapper reagent.impl.component/custom-wrapper
        real-next-tick      reagent.impl.batching/next-tick
        real-schedule       reagent.impl.batching/schedule]


    #_(set! reagent.impl.component/do-render
            (fn [c]
              (let [name (comp-name c)]
                (js/console.log c)
                (trace/with-trace {:op-type   :render
                                   :tags      {:component-path (component-path c)}
                                   :operation (last (str/split name #" > "))}
                                  (real-renderer c)))))



    (set! reagent.impl.component/static-fns static-fns)

    (set! reagent.impl.component/custom-wrapper
          (fn [key f]
            (case key
              :componentWillUnmount
              (fn [] (this-as c
                       (trace/with-trace {:op-type   key
                                          :operation (last (str/split (comp-name c) #" > "))
                                          :tags      {:component-path (component-path c)
                                                      :reaction       (interop/reagent-id ($ c :cljsRatom))}})
                       (.call (real-custom-wrapper key f) c c)))

              (real-custom-wrapper key f))))

    #_(set! reagent.impl.batching/next-tick (fn [f]
                                              (real-next-tick (fn []
                                                                (trace/with-trace {:op-type :raf}
                                                                                  (f))))))

    #_(set! reagent.impl.batching/schedule schedule
            #_(fn []
                (reagent.impl.batching/do-after-render (fn [] (trace/with-trace {:op-type :raf-end})))
                (real-schedule)))))

(def total-traces (interop/ratom 0))
(def traces (interop/ratom []))

(defn log-trace? [trace]
  (let [rendering? (= (:op-type trace) :render)]
    (if-not rendering?
      true
      (not (str/includes? (get-in trace [:tags :component-path] "") "devtools outer")))


    #_(if-let [comp-p (get-in trace [:tags :component-path])]
        (println comp-p))))

(defn disable-tracing! []
  (re-frame.trace/remove-trace-cb ::cb))

(defn enable-tracing! []
  (re-frame.trace/register-trace-cb ::cb (fn [new-traces]
                                           (when-let [new-traces (filter log-trace? new-traces)]
                                             (swap! total-traces + (count new-traces))
                                             (swap! traces (fn [existing]
                                                             (let [new  (reduce conj existing new-traces)
                                                                   size (count new)]
                                                               (if (< 4000 size)
                                                                 (let [new2 (subvec new (- size 2000))]
                                                                   (if (< @total-traces 20000) ;; Create a new vector to avoid structurally sharing all traces forever
                                                                     (do (reset! total-traces 0)
                                                                         (into [] new2))))
                                                                 new))))))))

(defn init-tracing!
  "Sets up any initial state that needs to be there for tracing. Does not enable tracing."
  []
  (monkey-patch-reagent))


(defn resizer-style [draggable-area]
  {:position "absolute" :z-index 2 :opacity 0
   :left     (str (- (/ draggable-area 2)) "px") :width "10px" :top "0px" :height "100%" :cursor "col-resize"})

(def ease-transition "left 0.2s ease-out, top 0.2s ease-out, width 0.2s ease-out, height 0.2s ease-out")

(defn toggle-traces [showing?]
  (if @showing?
    (enable-tracing!)
    (disable-tracing!)))

(defn devtools []
  ;; Add clear button
  ;; Filter out different trace types
  (let [position             (r/atom :right)
        panel-width%         (r/atom (localstorage/get "panel-width-ratio" 0.35))
        showing?             (r/atom (localstorage/get "show-panel" false))
        dragging?            (r/atom false)
        pin-to-bottom?       (r/atom true)
        selected-tab         (r/atom (localstorage/get "selected-tab" :traces))
        window-width         (r/atom js/window.innerWidth)
        handle-window-resize (fn [e]
                               ;; N.B. I don't think this should be a perf bottleneck.
                               (reset! window-width js/window.innerWidth))
        handle-keys          (fn [e]
                               (let [combo-key?      (or (.-ctrlKey e) (.-metaKey e) (.-altKey e))
                                     tag-name        (.-tagName (.-target e))
                                     key             (.-key e)
                                     entering-input? (contains? #{"INPUT" "SELECT" "TEXTAREA"} tag-name)]
                                 (when (and (not entering-input?) combo-key?)
                                   (cond
                                     (and (= key "h") (.-ctrlKey e))
                                     (do (swap! showing? not)
                                         (toggle-traces showing?)
                                         (.preventDefault e))))))
        handle-mousemove     (fn [e]
                               (when @dragging?
                                 (let [x                (.-clientX e)
                                       y                (.-clientY e)
                                       new-window-width js/window.innerWidth]
                                   (.preventDefault e)
                                   ;; Set a minimum width of 5% to prevent people from accidentally dragging it too small.
                                   (reset! panel-width% (max (/ (- new-window-width x) new-window-width) 0.05))
                                   (reset! window-width new-window-width))))
        handle-mouse-up      (fn [e] (reset! dragging? false))]
    (add-watch panel-width%
               :update-panel-width-ratio
               (fn [_ _ _ new-state]
                 (localstorage/save! "panel-width-ratio" new-state)))
    (add-watch showing?
               :update-show-panel
               (fn [_ _ _ new-state]
                 (localstorage/save! "show-panel" new-state)))
    (add-watch selected-tab
               :update-selected-tab
               (fn [_ _ _ new-state]
                 (localstorage/save! "selected-tab" new-state)))
    (r/create-class
      {:component-will-mount   (fn []
                                 (toggle-traces showing?)
                                 (js/window.addEventListener "keydown" handle-keys)
                                 (js/window.addEventListener "mousemove" handle-mousemove)
                                 (js/window.addEventListener "mouseup" handle-mouse-up)
                                 (js/window.addEventListener "resize" handle-window-resize))
       :component-will-unmount (fn []
                                 (js/window.removeEventListener "keydown" handle-keys)
                                 (js/window.removeEventListener "mousemove" handle-mousemove)
                                 (js/window.removeEventListener "mouseup" handle-mouse-up)
                                 (js/window.removeEventListener "resize" handle-window-resize))
       :display-name           "devtools outer"
       :reagent-render         (fn []
                                 (let [draggable-area 10
                                       left           (if @showing? (str (* 100 (- 1 @panel-width%)) "%")
                                                                    (str @window-width "px"))
                                       transition     (if @dragging?
                                                        ""
                                                        ease-transition)]
                                   [:div.panel-wrapper
                                    {:style {:position "fixed" :width "0px" :height "0px" :top "0px" :left "0px" :z-index 99999999}}
                                    [:div.panel
                                     {:style {:position   "fixed" :z-index 1 :box-shadow "rgba(0, 0, 0, 0.3) 0px 0px 4px" :background "white"
                                              :left       left :top "0px" :width (str (inc (int (* 100 @panel-width%))) "%") :height "100%"
                                              :transition transition}}
                                     [:div.panel-resizer {:style         (resizer-style draggable-area)
                                                          :on-mouse-down #(reset! dragging? true)}]
                                     [:div.panel-content
                                      {:style {:width "100%" :height "100%" :display "flex" :flex-direction "column"}}
                                      [:div.panel-content-top
                                       [:div.nav
                                        [:button {:class    (str "tab button " (when (= @selected-tab :traces) "active"))
                                                  :on-click #(reset! selected-tab :traces)} "Traces"]
                                        [:button {:class    (str "tab button " (when (= @selected-tab :app-db) "active"))
                                                  :on-click #(reset! selected-tab :app-db)} "App DB"]
                                        #_[:button {:class    (str "tab button " (when (= @selected-tab :subvis) "active"))
                                                    :on-click #(reset! selected-tab :subvis)} "SubVis"]]]
                                      (case @selected-tab
                                        :traces [traces/render-trace-panel traces]
                                        :app-db [app-db/render-state db/app-db]
                                        :subvis [subvis/render-subvis traces
                                                 [:div.panel-content-scrollable]]
                                        [app-db/render-state db/app-db])]]]))})))

(defn panel-div []
  (let [id    "--re-frame-trace--"
        panel (.getElementById js/document id)]
    (if panel
      panel
      (let [new-panel (.createElement js/document "div")]
        (.setAttribute new-panel "id" id)
        (.appendChild (.-body js/document) new-panel)
        (js/window.focus new-panel)
        new-panel))))

(defn inject-styles []
  (let [id            "--re-frame-trace-styles--"
        styles-el     (.getElementById js/document id)
        new-styles-el (.createElement js/document "style")
        new-styles    styles/panel-styles]
    (.setAttribute new-styles-el "id" id)
    (-> new-styles-el
        (.-innerHTML)
        (set! new-styles))
    (if styles-el
      (-> styles-el
          (.-parentNode)
          (.replaceChild new-styles-el styles-el))
      (let []
        (.appendChild (.-head js/document) new-styles-el)
        new-styles-el))))

(defn inject-devtools! []
  (inject-styles)
  (r/render [devtools] (panel-div)))
