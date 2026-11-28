#!/usr/bin/env bash
set -euo pipefail
tests=(ActorSupervisorControlLanePortTest ActorSupervisorControlAtomicTransferTest ActorSupervisorControlCounterSaturationTest ActorSupervisorControlV2Test ActorSupervisorControlConservationTest)
for t in "${tests[@]}"; do rm -f "target/surefire-reports/TEST-dev.oreslang.runtime.$t.xml"; done
selection=$(IFS=,; echo "${tests[*]}")
mvn -B -ntp -Dores.runtime.carriers=native -Dtest="$selection" -DfailIfNoTests=true test
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
cat > "$tmp/Verify.java" <<'JAVA'
import java.nio.file.*;
import java.util.*;
import javax.xml.parsers.*;
class Verify {
  public static void main(String[] args) throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    var expected = Set.of("missingSecondRootReservationRejectsWholeTransferWithoutPartialMove",
      "staleOrOccupiedRootReservationNeverConsumesParentNotice", "transferredInFlightNoticeCannotBeDroppedByParentAck",
      "opposingTransfersDoNotDeadlockAndRetainEveryNotice", "staleAndForeignSettlementLeasesCannotAckANewerDelivery",
      "deliveryTicketOverflowDoesNotDequeueOrLoseTheControlNotice", "failedReceiptConstructionPreservesPendingNotice");
    for (String test : args) {
      Path p = Path.of("target/surefire-reports/TEST-dev.oreslang.runtime." + test + ".xml");
      if (!Files.isRegularFile(p) || Files.isSymbolicLink(p)) throw new AssertionError("missing report: " + test);
      var root = factory.newDocumentBuilder().parse(p.toFile()).getDocumentElement();
      var cases = root.getElementsByTagName("testcase");
      if (!root.getTagName().equals("testsuite") || cases.getLength() == 0
          || Integer.parseInt(root.getAttribute("tests")) != cases.getLength()
          || !root.getAttribute("errors").equals("0") || !root.getAttribute("failures").equals("0")
          || !root.getAttribute("skipped").equals("0") || root.getElementsByTagName("failure").getLength() != 0
          || root.getElementsByTagName("error").getLength() != 0 || root.getElementsByTagName("skipped").getLength() != 0)
        throw new AssertionError("invalid report: " + test);
      if (test.equals("ActorSupervisorControlLanePortTest")) {
        var names = new HashSet<String>();
        for (int i=0;i<cases.getLength();i++) names.add(((org.w3c.dom.Element)cases.item(i)).getAttribute("name"));
        if (cases.getLength()!=expected.size() || !names.equals(expected)) throw new AssertionError("wrong regression set");
      }
      System.out.println("SUPERVISOR_CONTROL_JUNIT_PASS " + test + " tests=" + cases.getLength());
    }
  }
}
JAVA
java "$tmp/Verify.java" "${tests[@]}"
