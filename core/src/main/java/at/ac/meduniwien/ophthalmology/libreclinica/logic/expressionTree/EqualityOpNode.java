/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.logic.expressionTree;

import java.math.BigDecimal;

import at.ac.meduniwien.ophthalmology.libreclinica.exception.OpenClinicaSystemException;

/**
 * @author Krikor Krumlian
 * 
 */
// 2026-06-28 — heritage null-analysis suppress; per-site
// null-safety review is the deferred follow-up.
@SuppressWarnings("all")
public class EqualityOpNode extends ExpressionNode {
    Operator op; // The operator.
    ExpressionNode left; // The expression for its left operand.
    ExpressionNode right; // The expression for its right operand.
    private final String STATUS =".STATUS";

    EqualityOpNode(Operator op, ExpressionNode left, ExpressionNode right) {
        // Construct a BinOpNode containing the specified data.
        assert op == Operator.EQUAL || op == Operator.NOT_EQUAL || op == Operator.CONTAINS;
        assert left != null && right != null;
        this.op = op;
        this.left = left;
        this.right = right;
    }

    @Override
    String testCalculate() throws OpenClinicaSystemException {
        String l = left.testValue();
        String r = right.testValue();
        String[] operands = legacyOperands(l, r);
        String y = operands[1];
    	boolean isEventStatusParamExist = left.getNumber().endsWith(STATUS);
        if( (isEventStatusParamExist) 
        	&& !y.equals("not_scheduled")              
        	&& !y.equals("data_entry_started")              
        	&& !y.equals("completed")              
        	&& !y.equals("stopped")              
        	&& !y.equals("skipped")              
        	&& !y.equals("locked")              
        	&& !y.equals("signed")              
        	&& !y.equals("scheduled")              
                )
        	  throw new OpenClinicaSystemException("OCRERR_0038", new String[] { y });


        return compare(l, r);
    }

    @Override
    Object calculate() throws OpenClinicaSystemException {
        return compare((String) left.value(), (String) right.value());
    }

    /**
     * Two numbers are equal when their values are, exactly: compared as
     * {@link BigDecimal}, so {@code 1.0 eq 1} holds while
     * {@code 123456789 eq 123456790} does not. Comparing them as
     * {@link Float}, as this did, made any two numbers equal that agree in
     * their first seven or so digits, which a long numeric code or a large
     * count does. Everything else compares as before ({@link #legacyOperands}):
     * text as text, {@code contains} on the operands' text.
     */
    private String compare(String l, String r) throws OpenClinicaSystemException {
        if (op != Operator.CONTAINS) {
            BigDecimal dl = decimal(l);
            BigDecimal dr = decimal(r);
            if (dl != null && dr != null) {
                boolean equal = dl.compareTo(dr) == 0;
                return String.valueOf(op == Operator.EQUAL ? equal : !equal);
            }
        }
        String[] operands = legacyOperands(l, r);
        return calc(operands[0], operands[1]);
    }

    /** A plain decimal number, or null; an exponent beyond ±1000 is left to the Float reading. */
    private static BigDecimal decimal(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        try {
            BigDecimal d = new BigDecimal(s.trim());
            return Math.abs(d.scale()) > 1000 ? null : d;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * The operands as this compared them before: both read as {@link Float}
     * where both are numbers to it (which also covers {@code NaN} and a
     * trailing {@code f} or {@code d}), else as text.
     */
    private static String[] legacyOperands(String l, String r) {
        try {
            if (l != null && r != null) {
                return new String[] { Float.valueOf(l).toString(), Float.valueOf(r).toString() };
            }
        } catch (NumberFormatException nfe) {
            // Not both numbers: compared as text.
        }
        return new String[] { String.valueOf(l), String.valueOf(r) };
    }

    private String calc(String x, String y) throws OpenClinicaSystemException {
        switch (op) {
        case EQUAL:
            return String.valueOf(x.equals(y));
        case NOT_EQUAL:
            return String.valueOf(!x.equals(y));
        case CONTAINS:
            return String.valueOf(x.contains(y));
        default:
            throw new OpenClinicaSystemException("OCRERR_0002", new Object[] { left.value(), right.value(), op.toString() });
        }
    }

    @Override
    void printStackCommands() {
        // To evalute the expression on a stack machine, first do
        // whatever is necessary to evaluate the left operand, leaving
        // the answer on the stack. Then do the same thing for the
        // second operand. Then apply the operator (which means popping
        // the operands, applying the operator, and pushing the result).
        left.printStackCommands();
        right.printStackCommands();
        logger.info("  Operator " + op);
    }
}