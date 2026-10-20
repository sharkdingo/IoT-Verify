package cn.edu.nju.Iot_Verify.component.nusmv.fixer.parameterize;

import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts repair parameter values from NuSMV counterexample output.
 *
 * <p>Free FROZENVAR parameters and fixed DEFINE switches appear in State 1.1 as assignments.
 * This extractor uses lightweight regex matching on the raw NuSMV output
 * rather than full trace parsing.</p>
 */
@Slf4j
public class ParameterExtractor {

    // Matches lines like "    param_r0_c1 = 25" or "    lambda_r0_c0 = TRUE"
    private static final Pattern ASSIGNMENT_PATTERN =
            Pattern.compile("^\\s+(\\S+)\\s*=\\s*(\\S+)\\s*$", Pattern.MULTILINE);

    /**
     * Extract specified repair parameter values from NuSMV raw output.
     *
     * @param nusmvOutput    raw NuSMV stdout
     * @param frozenVarNames list of parameter names to look for
     * @return map of varName → value (only includes found variables)
     */
    public static Map<String, String> extract(String nusmvOutput, List<String> frozenVarNames) {
        Map<String, String> result = new LinkedHashMap<>();
        if (nusmvOutput == null || nusmvOutput.isBlank() || frozenVarNames == null || frozenVarNames.isEmpty()) {
            return result;
        }

        // Read the first state so a later assignment cannot replace the chosen repair.
        int stateStart = nusmvOutput.indexOf("-> State:");
        if (stateStart < 0) {
            // Try alternate format
            stateStart = nusmvOutput.indexOf("State 1.1");
        }
        if (stateStart < 0) {
            log.debug("No state section found in NuSMV output");
            return result;
        }

        // Find the end of this state section (next "-> State:" or "-> Input:" or end of counterexample)
        int nextSection = findNextSection(nusmvOutput, stateStart + 1);
        String stateSection = (nextSection > 0)
                ? nusmvOutput.substring(stateStart, nextSection)
                : nusmvOutput.substring(stateStart);

        // Use Set for O(1) lookup
        Set<String> varNameSet = new HashSet<>(frozenVarNames);

        Matcher matcher = ASSIGNMENT_PATTERN.matcher(stateSection);
        while (matcher.find()) {
            String varName = matcher.group(1);
            String value = matcher.group(2);
            if (varNameSet.contains(varName)) {
                result.put(varName, value);
            }
        }

        if (result.size() < frozenVarNames.size()) {
            log.debug("Extracted {}/{} repair parameter values from NuSMV output", result.size(), frozenVarNames.size());
        }
        return result;
    }

    private static int findNextSection(String output, int from) {
        int nextState = output.indexOf("-> State:", from);
        int nextInput = output.indexOf("-> Input:", from);
        int loopStart = output.indexOf("-- Loop starts here", from);

        int min = -1;
        if (nextState > 0) min = nextState;
        if (nextInput > 0 && (min < 0 || nextInput < min)) min = nextInput;
        if (loopStart > 0 && (min < 0 || loopStart < min)) min = loopStart;
        return min;
    }
}
