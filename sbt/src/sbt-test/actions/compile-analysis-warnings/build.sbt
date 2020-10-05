scalacOptions := { Seq("-Xlint:unused") }

val check = taskKey[Unit]("check")

check := {
    val infos = (Compile / compile).value.readSourceInfos.getAllSourceInfos.values
    if (infos.iterator.next().getReportedProblems.isEmpty) {
        sys.error("no warnings: " + infos)
    }
}
