/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.dao.extract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.FilterObjectBean;
import org.junit.Test;

/**
 * The legacy Create Filter wizard (CreateFiltersTwo) turns form fields into a
 * SQL fragment. The connector and operators must come from the form's fixed
 * choices, and the compared value must stay inside its string literal.
 */
public class FilterSqlGenerationTest {

    private final FilterDAO dao = new FilterDAO(null, null);

    private static FilterObjectBean criterion(int itemId, String operand, String value) {
        FilterObjectBean fob = new FilterObjectBean();
        fob.setItemId(itemId);
        fob.setOperand(operand);
        fob.setValue(value);
        return fob;
    }

    private static ArrayList<FilterObjectBean> criteria(FilterObjectBean... fobs) {
        return new ArrayList<>(List.of(fobs));
    }

    @Test
    public void legitimateCriteriaProduceTheSameSqlAsBefore() {
        String sql = dao.genSQLStatement(null, "and",
                criteria(criterion(5, "=", "abc"), criterion(6, " like ", "x")));
        assertEquals(" and subject_id in (select subject_id from extract_data_table where "
                + "(((item_id = 5 and value = 'abc')) and (item_id = 6 and value  like  '%x%'))", sql);

        String or = dao.genSQLStatement(null, "or", criteria(criterion(5, "!=", "1"), criterion(6, "<=", "2")));
        assertTrue(or, or.contains("')) or (item_id = 6"));
    }

    @Test
    public void quotesInTheValueStayInsideTheLiteral() {
        String sql = dao.genSQLStatement(null, "and", criteria(criterion(5, "=", "x' or '1'='1")));
        assertTrue(sql, sql.contains("value = 'x'' or ''1''=''1'))"));
        assertFalse(sql, sql.contains("'x' or"));
    }

    @Test
    public void connectorOutsideTheFormChoicesIsRefused() {
        try {
            dao.genSQLStatement(null, "and 1=1) or (1",
                    criteria(criterion(5, "=", "a"), criterion(6, "=", "b")));
            fail("free-text connector accepted");
        } catch (IllegalArgumentException expected) {
            // refused
        }
    }

    @Test
    public void operatorOutsideTheFormChoicesIsRefused() {
        try {
            dao.genSQLStatement(null, "and", criteria(criterion(5, "= 'a' or 1=1 --", "a")));
            fail("free-text operator accepted");
        } catch (IllegalArgumentException expected) {
            // refused
        }
    }
}
