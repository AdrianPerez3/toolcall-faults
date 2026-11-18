# Only complete, fixed-format summary lines or counts are suitable for the public log.
function is_cell(key,    parts) {
    return split(key, parts, "[|]") == 7 && parts[1] ~ /^[^@| ]+@[^@| ]+$/ && parts[6] ~ /^jdk[0-9]+$/
}

phase ~ /^selftest/ {
    if ($0 ~ /^server: [0-9]+ of [0-9]+ fixtures byte-exact$/ ||
        $0 ~ /^controls: [0-9]+ of [0-9]+ EXECUTED_AS_SENT; [0-9]+ declared$/ ||
        $0 ~ /^anchors: [0-9]+ of [0-9]+ matched \(JDK [0-9]+; probe JDK 21\)$/ ||
        $0 ~ /^pins: [0-9]+ of [0-9]+ DIFFERS$/) {
        print
        selftest[$1]++
    }
    if ($0 ~ /HARNESS/) harness_lines++
}

phase == "run" && /^run: [0-9]+ cells x [0-9]+ repetitions, [0-9]+ HARNESS$/ {
    print
    summaries++
}
phase == "run" && /^controls: [0-9]+ unexpected repetitions; inspect local evidence$/ {
    controls = $2
}
phase == "run" && /^FLAKY [^ ]+ \{[A-Z_]+=[0-9]+(, [A-Z_]+=[0-9]+)*\}$/ && is_cell($2) {
    unstable++
}

phase == "check" && $1 ~ /^(CHANGED|NEW|STALE|INCOMPLETE|FLAKY|HARNESS)$/ && NF >= 3 && is_cell($2) {
    findings[$1]++
    if (!($2 in seen)) {
        seen[$2] = 1
        differing++
    }
}
phase == "check" && $0 == "Run does not contain the complete published current matrix" {
    coverage_incomplete = 1
}

END {
    if (phase == "selftest") {
        if (selftest["server:"] == 1 && selftest["controls:"] == 1 &&
            selftest["anchors:"] == 1 && selftest["pins:"] == 1) exit 0
        print "selftest summary: expected one result for server, controls, anchors and pins"
        exit 1
    }
    if (phase == "selftest-failed") {
        printf "harness lines: %d\n", harness_lines
        print "selftest failed; details stay in the workspace"
    } else if (phase == "run") {
        if (summaries) {
            printf "unstable cells: %d\n", unstable
            printf "unexpected control repetitions: %d\n", controls
        } else {
            print "run ended before its final summary"
        }
        print "run failed; details stay in the workspace"
    } else if (phase == "check") {
        printf "differing cells: %d\n", differing
        printf "findings: CHANGED=%d NEW=%d STALE=%d INCOMPLETE=%d FLAKY=%d HARNESS=%d\n", \
            findings["CHANGED"], findings["NEW"], findings["STALE"], \
            findings["INCOMPLETE"], findings["FLAKY"], findings["HARNESS"]
        if (coverage_incomplete) print "published coverage: incomplete"
        print "check failed; details stay in the workspace"
    }
}
