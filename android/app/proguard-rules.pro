# R8 shrinks and obfuscates the release app (renames classes and methods, removes unused code).
# Activities, services and other components named in AndroidManifest.xml are kept automatically.
# WellShare uses no reflection, so no extra keep rules are needed.

# Keep line numbers in crash reports, but hide the original file names.
-keepattributes LineNumberTable
-renamesourcefileattribute SourceFile
