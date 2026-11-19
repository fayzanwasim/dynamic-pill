# R8 rules for the release build.
#
# The app has no reflection or serialization: every entry point (activities, services, the receiver and the
# Quick Settings tile) is declared in the manifest, which R8 keeps automatically, so no extra rules are needed.
