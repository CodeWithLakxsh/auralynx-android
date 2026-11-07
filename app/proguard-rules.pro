# Auralynx R8 rules.
# kotlinx.serialization: keep generated serializers for @Serializable navigation routes and stored JSON.
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class **$$serializer { *; }
# Diagnostic stack traces keep line numbers; source file names are replaced.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
