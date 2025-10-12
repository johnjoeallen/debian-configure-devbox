#!/usr/bin/env groovy
// --- Documentation ---
// Summary: Ensure the invoking user belongs to the docker Unix group.
// Config keys: none
// Notes: Adds the group if needed and reminds the user to re-login for membership.

import static lib.StepUtils.sh

def user = System.getenv("USER")
def inGroup = (sh("id -nG ${user} | tr ' ' '\\n' | grep -qx docker").code==0)
if (inGroup) System.exit(0)
if (sh("sudo groupadd -f docker").code!=0) System.exit(1)
if (sh("sudo usermod -aG docker ${user}").code!=0) System.exit(1)
println "Added ${user} to docker group. Log out/in to apply."
System.exit(10)
