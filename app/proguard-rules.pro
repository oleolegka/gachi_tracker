# What this file is for, and what it deliberately does not do.
#
# Shrinking is on; RENAMING IS NOT. -dontobfuscate keeps every class and member under
# its own name, which removes the entire class of failure this exercise is afraid of:
# a missing keep rule turning into a crash somewhere far from what was cut. The size win
# comes mostly from dropping unreachable code anyway.
#
# There are NO hand-written keep rules for Room or kotlinx.serialization on purpose.
# Both ship consumer rules inside their own artifacts, and this app has no reflection of
# its own: no Class.forName, no getDeclaredField/Method, no newInstance. Every ::class.java
# in the source is an Intent, a system service or the Room builder - references R8 traces
# without help. All 35 @Serializable types sit in SEALED hierarchies, whose serializers the
# compiler plugin wires statically; there is no SerializersModule, no open polymorphism and
# no contextual serializer anywhere.
#
# Adding speculative keeps would hide exactly the answer this build is meant to produce.
# If something breaks on the phone, the rule that fixes it belongs here WITH the symptom
# written next to it.
-dontobfuscate

# The list of everything R8 decided was unreachable. Read it before trusting the result:
# it is the only way to see, without a device, that nothing of ours was dropped.
-printusage build/outputs/r8-removed.txt
