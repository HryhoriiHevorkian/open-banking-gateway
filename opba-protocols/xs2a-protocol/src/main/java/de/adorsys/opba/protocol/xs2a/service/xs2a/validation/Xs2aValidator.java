package de.adorsys.opba.protocol.xs2a.service.xs2a.validation;

import com.google.common.collect.Iterables;
import de.adorsys.opba.protocol.api.dto.ValidationIssue;
import de.adorsys.opba.protocol.api.dto.codes.FieldCode;
import de.adorsys.opba.protocol.api.services.scoped.validation.FieldsToIgnoreLoader;
import de.adorsys.opba.protocol.api.services.scoped.validation.IgnoreValidationRule;
import de.adorsys.opba.protocol.bpmnshared.dto.context.BaseContext;
import de.adorsys.opba.protocol.bpmnshared.service.context.ContextUtil;
import de.adorsys.opba.protocol.xs2a.context.Xs2aContext;
import de.adorsys.opba.protocol.xs2a.domain.ValidationIssueException;
import de.adorsys.opba.protocol.xs2a.service.xs2a.annotations.ConditionProvider;
import de.adorsys.opba.protocol.xs2a.service.xs2a.annotations.ValidationInfo;
import de.adorsys.opba.protocol.xs2a.service.xs2a.dto.ValidationMode;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.delegate.DelegateExecution;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.ReflectionUtils;

import javax.validation.ConstraintViolation;
import javax.validation.Validator;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static de.adorsys.opba.protocol.bpmnshared.dto.context.ContextMode.REAL_CALLS;


/**
 * Key validation service that uses Hibernate-validator to check that required parameters are available before doing
 * ASPSP API call.
 * For {@link de.adorsys.opba.protocol.bpmnshared.dto.context.ContextMode#MOCK_REAL_CALLS} collects
 * all violations into the context to emit message that requires user to provide inputs that fix the violations.
 * For {@link de.adorsys.opba.protocol.bpmnshared.dto.context.ContextMode#REAL_CALLS} causes Runtime error
 * if API object fails the validation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Xs2aValidator {

    private final Validator validator;

    /**
     * Validates that all parameters necessary to perform ASPSP API call is present.
     * In {@link de.adorsys.opba.protocol.bpmnshared.dto.context.ContextMode#MOCK_REAL_CALLS}
     * reports all violations into {@link BaseContext#getViolations()} (merging with already existing ones)
     *
     * @param exec           Current execution that will be updated with violations if present.
     * @param dtosToValidate ASPSP API call parameter objects to validate.
     */
    public <T> void validate(DelegateExecution exec, Xs2aContext context, Class<T> invokerClass, Object... dtosToValidate) {
        Set<ConstraintViolation<Object>> allErrors = new HashSet<>();

        FieldsToIgnoreLoader fieldsToIgnoreLoader = context.getRequestScoped().fieldsToIgnoreLoader();
        Map<FieldCode, IgnoreValidationRule> rulesMap = fieldsToIgnoreLoader.getIgnoreValidationRules(
                invokerClass,
                context.getActiveScaApproach()
        );
        for (Object value : dtosToValidate) {
            Set<ConstraintViolation<Object>> errors = validator.validate(value)
                    .stream()
                    .filter(f -> doNotIgnoreValidationError(f, rulesMap))
                    .collect(Collectors.toSet());
            allErrors.addAll(errors);
        }

        if (allErrors.isEmpty()) {
            return;
        }

        ContextUtil.getAndUpdateContext(
                exec,
                (BaseContext ctx) -> {
                    ctx.getViolations().addAll(allErrors.stream()
                            .map(it -> applyConditionalValidation(context, it))
                            .filter(Objects::nonNull)
                            .collect(Collectors.toSet()));
                    // Only when doing real calls validations cause termination of flow
                    // TODO: Those validation in real call should be propagated and handled
                    if (REAL_CALLS == ctx.getMode()) {
                        log.error("Fatal validation error for requestId={},sagaId={} - violations {}", ctx.getRequestId(), ctx.getSagaId(), allErrors);
                        throw new ValidationIssueException();
                    }
                }
        );
    }

    @Nullable
    @SneakyThrows
    private ValidationIssue applyConditionalValidation(Xs2aContext context, ConstraintViolation<Object> it) {
        ValidationInfo info = findInfoOnViolation(it);
        if (info.validationMode() == ValidationMode.CONDITIONAL) {
            ConditionProvider provider = info.condition().getDeclaredConstructor().newInstance();
            return provider.isMandatory(context) ? toIssue(it, info) : null;
        }
        return toIssue(it, info);
    }


    private ValidationIssue toIssue(ConstraintViolation<Object> violation, ValidationInfo info) {
        return ValidationIssue.builder()
                .type(info.ui().value())
                .scope(info.ctx().target())
                .code(info.ctx().value())
                .captionMessage(violation.getMessage())
                .build();
    }

    private boolean doNotIgnoreValidationError(ConstraintViolation<Object> constraint, Map<FieldCode, IgnoreValidationRule> rulesMap) {
        ValidationInfo info = findInfoOnViolation(constraint);
        boolean doNotIgnoreValidationError = info.validationMode() == ValidationMode.MANDATORY;

        FieldCode fieldCode = info.ctx().value();
        IgnoreValidationRule ignoreRule = rulesMap.get(fieldCode);
        if (ignoreRule != null) {
            doNotIgnoreValidationError = ignoreRule.applies();
        }

        return doNotIgnoreValidationError;
    }

    @SneakyThrows
    private ValidationInfo findInfoOnViolation(ConstraintViolation<Object> violation) {
        String name = Iterables.getLast(violation.getPropertyPath()).getName();
        Field fieldValue = ReflectionUtils.findField(violation.getLeafBean().getClass(), name);

        if (null == fieldValue) {
            throw new IllegalStateException("Validated field not found " + name);
        }

        if (!fieldValue.isAnnotationPresent(ValidationInfo.class)) {
            throw new IllegalStateException("Field " + name + " not annotated with @ValidationInfo");
        }

        return fieldValue.getAnnotationsByType(ValidationInfo.class)[0];
    }
}
