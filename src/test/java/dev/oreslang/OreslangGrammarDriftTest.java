package dev.oreslang;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Prevent silent additions or removals to documented Oreslang grammar productions. */
final class OreslangGrammarDriftTest {
    private static final String REVIEWED_PRODUCTIONS = """
            source namespace_decl import_decl import_spec import_kind import_names
            top_level_item annotated_module_decl module_member visibility callable_modifier method_modifier
            callable_decl callable_kind actor_callable_decl actor_decl actor_body actor_member
            final_actor_field_decl actor_method_decl class_decl class_member constructor_decl field_decl
            type_first_class_field_decl method_decl
            method_name callable_name method_head explicit_receiver static_function_decl contract_decl
            interface_decl interface_body interface_member type_decl annotation annotation_args
            annotation_arg return_spec type_return_spec generic_params param_list param
            binding_decl binding_kind named_inferred_binding named_inferred_literal destructure_stmt sequence_pattern
            object_pattern sequence_destructure_item sequence_rest_item object_destructure_item object_rest_item block
            statement return_stmt defer_stmt block_stmt break_stmt continue_stmt
            loop_stmt while_stmt if_stmt conditional_branch deprecated_if_do condition_head condition condition_and
            match_stmt match_arm pattern switch_stmt switch_case switch_default
            try_stmt select_stmt select_wait_mode select_policy select_arm select_arm_keyword
            select_default for_stmt c_for_head for_init for_update for_of_head
            for_of_binding for_of_pattern for_of_destructure_item for_of_rest_item loop_body expression
            assignment conditional logic_or equality comparison additive
            multiplicative unary channel_expr channel_wait_mode postfix call_suffix
            call_argument_list call_argument member_suffix member_name index_suffix argument_list
            primary lambda pipe_lambda lambda_param typed_legacy_lambda lambda_body
            tuple_literal parenthesized_record_literal array_literal legacy_array_literal inferred_struct_literal explicit_struct_literal struct_field_spec
            struct_value_field static_struct_key literal type_ref type_atom borrow_type
            tuple_type record_type record_type_field alias_marker callable_generic_type named_type
            type_arg_or_named named_type_param meta_value sequence_type_shape sequence_type_item function_type
            grouped_type function_type_params function_type_param type_args qualified_name stmt_terminator
            member_terminator
            """;

    @Test
    void grammarProductionsNeedConformanceReviewOnChange() throws Exception {
        String grammar = Files.readString(Path.of("docs/grammar.ebnf"), StandardCharsets.UTF_8);
        // Productions may put '=' on the following line, which naive same-line
        // scans miss (e.g. actor_callable_decl and named_inferred_binding).
        Matcher matcher = Pattern.compile("(?m)^([a-z][a-z0-9_]*)[ \\t]*(?:\\R[ \\t]*)?=").matcher(grammar);
        Set<String> actual = new LinkedHashSet<>();
        while (matcher.find()) actual.add(matcher.group(1));
        Set<String> expected = new LinkedHashSet<>(Arrays.asList(REVIEWED_PRODUCTIONS.trim().split("\\s+")));
        assertEquals(expected, actual,
                "Grammar production set changed: review and update the conformance matrix and snapshot");
    }
}
