package com.autotest.contracts.extraction;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 受控正则编译器。
 *
 * <p>Java Pattern 是回溯型实现，不能为任意用户模式提供线性时间保证。因此平台
 * 只接受一个小而明确的安全子集：限制长度、量词数量和有限重复预算，拒绝量词
 * 相邻/嵌套、分组交替与量词组合、lookaround、反向引用和不完整结构。所有输入
 * 必须先通过本类的语法边界检查，才允许进入 {@link Pattern}。</p>
 */
public final class SafeRegex {

    /** 正则表达式的最大 UTF-16 长度。 */
    public static final int MAX_PATTERN_LENGTH = 2048;
    /** 旧调用方兼容别名；新代码应使用 {@link #MAX_PATTERN_LENGTH}。 */
    @Deprecated
    public static final int MAX_LENGTH = MAX_PATTERN_LENGTH;
    public static final int MAX_INPUT_LENGTH = 256 * 1024;

    private static final int MAX_QUANTIFIERS = 8;
    private static final int MAX_UNBOUNDED_QUANTIFIERS = 1;
    private static final int MAX_BOUNDED_REPETITION = 64;
    private static final int MAX_BOUNDED_REPETITION_BUDGET = 256;

    private SafeRegex() {
    }

    public static Pattern compile(String expression) {
        validate(expression);
        try {
            return Pattern.compile(expression);
        } catch (PatternSyntaxException exception) {
            throw new IllegalArgumentException("正则表达式不合法");
        } catch (StackOverflowError error) {
            // Pattern 本身是最后一道防线；正常情况下 validate 已在进入 Pattern 前拦截。
            throw new IllegalArgumentException("正则表达式编译超出受控限制");
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("正则表达式不合法");
        }
    }

    public static void ensureInputLength(String input) {
        if (input != null && input.length() > MAX_INPUT_LENGTH) {
            throw new IllegalArgumentException("正则匹配输入超过受控限制");
        }
    }

    private static void validate(String expression) {
        if (expression == null || expression.isBlank() || expression.length() > MAX_PATTERN_LENGTH) {
            throw new IllegalArgumentException("正则表达式为空或超过长度限制");
        }
        if (expression.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("正则表达式不得包含控制字符");
        }

        Deque<GroupState> groups = new ArrayDeque<>();
        Atom lastAtom = null;
        boolean previousAtomWasQuantified = false;
        boolean escaped = false;
        boolean inClass = false;
        boolean classHasContent = false;
        boolean topLevelBranchHasAtom = false;
        boolean topLevelHasAlternation = false;
        boolean topLevelHasQuantifier = false;
        int unboundedQuantifiers = 0;
        int quantifiers = 0;
        int boundedRepetitionBudget = 0;

        for (int index = 0; index < expression.length(); index++) {
            char value = expression.charAt(index);

            if (inClass) {
                if (escaped) {
                    index = consumeEscape(expression, index - 1, true);
                    escaped = false;
                    classHasContent = true;
                } else if (value == '\\') {
                    escaped = true;
                } else if (value == '[') {
                    // Nested/intersection character classes add another parser and are
                    // unnecessary for extraction; reject them to keep the grammar closed.
                    throw new IllegalArgumentException("正则字符类嵌套不受支持");
                } else if (value == ']') {
                    if (!classHasContent) throw new IllegalArgumentException("正则字符类不能为空");
                    inClass = false;
                    previousAtomWasQuantified = lastAtom != null && lastAtom.quantified();
                    lastAtom = Atom.simple();
                    markAtom(groups);
                    if (groups.isEmpty()) topLevelBranchHasAtom = true;
                } else {
                    classHasContent = true;
                }
                continue;
            }

            if (escaped) {
                index = consumeEscape(expression, index - 1, false);
                escaped = false;
                previousAtomWasQuantified = lastAtom != null && lastAtom.quantified();
                lastAtom = Atom.simple();
                markAtom(groups);
                if (groups.isEmpty()) topLevelBranchHasAtom = true;
                continue;
            }
            if (value == '\\') {
                escaped = true;
                continue;
            }
            if (value == '[') {
                inClass = true;
                classHasContent = false;
                previousAtomWasQuantified = lastAtom != null && lastAtom.quantified();
                lastAtom = null;
                continue;
            }
            if (value == '(') {
                if (index + 1 < expression.length() && expression.charAt(index + 1) == '?') {
                    throw new IllegalArgumentException("正则表达式不允许 lookaround 或特殊分组");
                }
                groups.push(new GroupState(previousAtomWasQuantified));
                lastAtom = null;
                previousAtomWasQuantified = false;
                continue;
            }
            if (value == ')') {
                if (groups.isEmpty()) throw new IllegalArgumentException("正则分组不完整");
                GroupState group = groups.pop();
                if (!group.branchHasAtom || (group.hasAlternation && group.containsQuantifier)) {
                    throw new IllegalArgumentException("正则分组结构不受支持");
                }
                if (lastAtom == null) throw new IllegalArgumentException("正则分组不能为空");
                lastAtom = Atom.group(group);
                previousAtomWasQuantified = group.precedingAtomWasQuantified;
                markAtom(groups);
                if (groups.isEmpty()) topLevelBranchHasAtom = true;
                continue;
            }
            if (value == '|') {
                if (lastAtom == null) throw new IllegalArgumentException("正则交替分支不能为空");
                if (groups.isEmpty()) {
                    if (!topLevelBranchHasAtom) throw new IllegalArgumentException("正则交替分支不能为空");
                    topLevelHasAlternation = true;
                    topLevelBranchHasAtom = false;
                } else {
                    GroupState group = groups.peek();
                    if (!group.branchHasAtom) throw new IllegalArgumentException("正则交替分支不能为空");
                    group.hasAlternation = true;
                    group.branchHasAtom = false;
                    if (group.branchHasQuantifier) {
                        throw new IllegalArgumentException("含量词的交替分支不受支持");
                    }
                    group.branchHasQuantifier = false;
                }
                lastAtom = null;
                previousAtomWasQuantified = false;
                continue;
            }
            if (isQuantifier(value)) {
                if (lastAtom == null || lastAtom.quantified() || previousAtomWasQuantified) {
                    throw new IllegalArgumentException("正则量词缺少安全目标");
                }
                Quantifier quantifier = consumeQuantifier(expression, index, value);
                if (quantifier.endIndex() > index) index = quantifier.endIndex();
                if (lastAtom.group() != null
                        && (lastAtom.group().hasAlternation || lastAtom.group().containsQuantifier)) {
                    throw new IllegalArgumentException("正则表达式不允许量词包裹交替或量词");
                }
                if (index + 1 < expression.length()
                        && (expression.charAt(index + 1) == '?' || expression.charAt(index + 1) == '+')) {
                    throw new IllegalArgumentException("正则量词修饰符不受支持");
                }
                quantifiers++;
                if (quantifier.unbounded()) unboundedQuantifiers++;
                if (!quantifier.unbounded()) boundedRepetitionBudget += quantifier.upper();
                if (unboundedQuantifiers > MAX_UNBOUNDED_QUANTIFIERS
                        || quantifiers > MAX_QUANTIFIERS
                        || boundedRepetitionBudget > MAX_BOUNDED_REPETITION_BUDGET) {
                    throw new IllegalArgumentException("正则表达式复杂度超过受控限制");
                }
                if (groups.isEmpty()) {
                    topLevelHasQuantifier = true;
                } else {
                    GroupState group = groups.peek();
                    group.containsQuantifier = true;
                    group.branchHasQuantifier = true;
                }
                lastAtom = lastAtom.withQuantified();
                continue;
            }

            // Ordinary literals, anchors and dot are all single, non-quantified atoms.
            previousAtomWasQuantified = lastAtom != null && lastAtom.quantified();
            lastAtom = Atom.simple();
            markAtom(groups);
            if (groups.isEmpty()) topLevelBranchHasAtom = true;
        }

        if (escaped || inClass || !groups.isEmpty() || lastAtom == null || !topLevelBranchHasAtom) {
            throw new IllegalArgumentException("正则表达式结构不完整");
        }
        if (topLevelHasAlternation && topLevelHasQuantifier) {
            throw new IllegalArgumentException("含量词的顶层交替不受支持");
        }
    }

    private static void markAtom(Deque<GroupState> groups) {
        if (!groups.isEmpty()) groups.peek().branchHasAtom = true;
    }

    private static int consumeEscape(String expression, int slashIndex, boolean inClass) {
        int escapedIndex = slashIndex + 1;
        if (escapedIndex >= expression.length()) throw new IllegalArgumentException("正则转义不完整");
        char value = expression.charAt(escapedIndex);
        if (Character.isDigit(value) || value == 'k' || value == 'g') {
            throw new IllegalArgumentException("正则表达式不允许反向引用");
        }
        if (value == 'p' || value == 'P') {
            int brace = escapedIndex + 1;
            int end = brace < expression.length() && expression.charAt(brace) == '{'
                    ? expression.indexOf('}', brace + 1) : -1;
            if (end <= brace + 1) throw new IllegalArgumentException("正则字符类不完整");
            return end;
        }
        if (value == 'Q') {
            int end = expression.indexOf("\\E", escapedIndex + 1);
            if (end < 0) throw new IllegalArgumentException("正则引用文本不完整");
            return end + 1;
        }
        if (value == 'u') return consumeHex(expression, escapedIndex, 4);
        if (value == 'x') {
            if (escapedIndex + 1 < expression.length() && expression.charAt(escapedIndex + 1) == '{') {
                int end = expression.indexOf('}', escapedIndex + 2);
                if (end <= escapedIndex + 2 || end - escapedIndex - 2 > 6) {
                    throw new IllegalArgumentException("正则十六进制转义不完整");
                }
                return end;
            }
            return consumeHex(expression, escapedIndex, 2);
        }
        if (!isSafeSingleEscape(value, inClass)) {
            throw new IllegalArgumentException("正则转义形式不受支持");
        }
        return escapedIndex;
    }

    private static int consumeHex(String expression, int escapedIndex, int digits) {
        int end = escapedIndex + digits;
        for (int index = escapedIndex + 1; index <= end; index++) {
            if (index >= expression.length() || Character.digit(expression.charAt(index), 16) < 0) {
                throw new IllegalArgumentException("正则十六进制转义不完整");
            }
        }
        return end;
    }

    private static boolean isSafeSingleEscape(char value, boolean inClass) {
        return "dDsSwWbBAZGzRtnrfae\\.^$|?*+()[]{}-".indexOf(value) >= 0;
    }

    private static Quantifier consumeQuantifier(String expression, int index, char value) {
        if (value == '{') {
            int end = expression.indexOf('}', index + 1);
            if (end < 0) throw new IllegalArgumentException("正则范围量词不完整");
            String range = expression.substring(index + 1, end);
            if (!range.matches("\\d+(,\\d*)?")) throw new IllegalArgumentException("正则范围量词不合法");
            String[] bounds = range.split(",", -1);
            int lower;
            try {
                lower = Integer.parseInt(bounds[0]);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("正则范围量词过大");
            }
            int upper = lower;
            boolean unbounded = bounds.length == 2 && bounds[1].isEmpty();
            if (bounds.length == 2 && !bounds[1].isEmpty()) {
                try {
                    upper = Integer.parseInt(bounds[1]);
                } catch (NumberFormatException exception) {
                    throw new IllegalArgumentException("正则范围量词过大");
                }
                if (upper < lower) throw new IllegalArgumentException("正则范围量词上下界不合法");
            }
            if (!unbounded && upper > MAX_BOUNDED_REPETITION) {
                throw new IllegalArgumentException("正则范围量词超过受控限制");
            }
            return new Quantifier(end, upper, unbounded);
        }
        return switch (value) {
            case '*' -> new Quantifier(index, 0, true);
            case '+' -> new Quantifier(index, 1, true);
            case '?' -> new Quantifier(index, 1, false);
            default -> throw new IllegalArgumentException("正则量词不合法");
        };
    }

    private static boolean isQuantifier(char value) {
        return value == '*' || value == '+' || value == '?' || value == '{';
    }

    private record Quantifier(int endIndex, int upper, boolean unbounded) {
    }

    private static final class GroupState {
        private final boolean precedingAtomWasQuantified;
        private boolean hasAlternation;
        private boolean containsQuantifier;
        private boolean branchHasAtom;
        private boolean branchHasQuantifier;

        private GroupState(boolean precedingAtomWasQuantified) {
            this.precedingAtomWasQuantified = precedingAtomWasQuantified;
        }
    }

    private record Atom(GroupState group, boolean quantified) {
        private static Atom simple() {
            return new Atom(null, false);
        }

        private static Atom group(GroupState group) {
            return new Atom(group, false);
        }

        private Atom withQuantified() {
            return new Atom(group, true);
        }
    }
}
