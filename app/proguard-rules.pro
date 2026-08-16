# Widget host views are instantiated reflectively by the framework in some paths.
-keep class com.marmic.plain.widget.PlainWidgetHostView { *; }

# kotlinx.serialization ships its own consumer rules; keep our @Serializable models
# intact so the layout JSON written by older builds still parses.
-keep,includedescriptorclasses class com.marmic.plain.model.** { *; }
