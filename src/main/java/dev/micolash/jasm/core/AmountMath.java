package dev.micolash.jasm.core;

import java.util.ArrayList;
import java.util.List;

/**
 * The sums a player may type where an amount to craft goes: whole numbers joined by {@code + - * /}, with no brackets.
 * {@code *} and {@code /} come before {@code +} and {@code -}, and each works left to right. A division rounds down. The
 * answer has to be between 1 and {@link #MOST}; anything else is a problem the screen can name, so it is never sent.
 */
public final class AmountMath {
    /** The biggest amount the boxes take: seven digits. */
    public static final long MOST = 9_999_999;
    /** Room for a sum in a box. */
    public static final int MAX_LENGTH = 24;

    public enum Problem {
        NONE,
        /** Nothing typed yet. */
        EMPTY,
        /** Not a sum: a sign with no number beside it, or something that isn't a digit or a sign. */
        BAD,
        DIVIDE_BY_ZERO,
        /** Zero or less. */
        TOO_SMALL,
        /** More than {@link #MOST}, or too big to work out. */
        TOO_BIG
    }

    /** The answer ({@code 0} when there is a problem), and whether the text used any sign, i.e. is more than a plain number. */
    public record Result(long value, Problem problem, boolean sum) {
        public boolean ok() {
            return problem == Problem.NONE;
        }
    }

    private AmountMath() {}

    /** Whether a key typed into an amount box is let through: a digit or one of the four signs. */
    public static boolean typable(int codePoint) {
        return codePoint >= '0' && codePoint <= '9' || isSign(codePoint);
    }

    private static boolean isSign(int c) {
        return c == '+' || c == '-' || c == '*' || c == '/';
    }

    public static Result evaluate(String text) {
        String s = text.strip();
        if (s.isEmpty()) {
            return new Result(0, Problem.EMPTY, false);
        }
        boolean sum = s.chars().anyMatch(AmountMath::isSign);
        // Digits, then a sign, then digits, and so on; spaces between them don't matter.
        List<Long> numbers = new ArrayList<>();
        List<Character> signs = new ArrayList<>();
        boolean tooBig = false;
        int i = 0;
        while (true) {
            while (i < s.length() && s.charAt(i) == ' ') i++;
            int start = i;
            while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
            if (i == start) {
                return new Result(0, Problem.BAD, sum);
            }
            String digits = s.substring(start, i).replaceFirst("^0+(?=.)", "");
            if (digits.length() > 18) {
                tooBig = true;
                numbers.add(0L);
            } else {
                numbers.add(Long.parseLong(digits));
            }
            while (i < s.length() && s.charAt(i) == ' ') i++;
            if (i == s.length()) {
                break;
            }
            if (!isSign(s.charAt(i))) {
                return new Result(0, Problem.BAD, sum);
            }
            signs.add(s.charAt(i++));
        }
        if (tooBig) {
            return new Result(0, Problem.TOO_BIG, sum);
        }
        try {
            long total = 0;
            long term = numbers.get(0);
            char add = '+';
            for (int n = 0; n < signs.size(); n++) {
                char sign = signs.get(n);
                long next = numbers.get(n + 1);
                if (sign == '*') {
                    term = Math.multiplyExact(term, next);
                } else if (sign == '/') {
                    if (next == 0) {
                        return new Result(0, Problem.DIVIDE_BY_ZERO, true);
                    }
                    term /= next;
                } else {
                    total = add == '+' ? Math.addExact(total, term) : Math.subtractExact(total, term);
                    add = sign;
                    term = next;
                }
            }
            total = add == '+' ? Math.addExact(total, term) : Math.subtractExact(total, term);
            if (total < 1) {
                return new Result(0, Problem.TOO_SMALL, sum);
            }
            if (total > MOST) {
                return new Result(0, Problem.TOO_BIG, sum);
            }
            return new Result(total, Problem.NONE, sum);
        } catch (ArithmeticException e) {
            return new Result(0, Problem.TOO_BIG, sum);
        }
    }
}
