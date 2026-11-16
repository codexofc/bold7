# gRPC finds its load balancers and name resolvers by class name on Android (LoadBalancerRegistry,
# NameResolverRegistry: hard-coded lists, no ServiceLoader). It was only tried unshrunk.
# All of io.grpc kept, a few hundred KB (simplest rule); narrow to the providers if the APK size matters.
-keep class io.grpc.** { *; }
-dontwarn io.grpc.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
