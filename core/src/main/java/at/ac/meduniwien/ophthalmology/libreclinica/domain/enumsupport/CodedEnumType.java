/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.domain.enumsupport;

import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Properties;

import org.hibernate.HibernateException;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.usertype.ParameterizedType;
import org.hibernate.usertype.UserType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * UserType to handle Coded Enumerations. Any enum that wants to persist via
 * this mechanism implements {@link CodedEnum} and exposes a static
 * {@code getByCode(Integer)} factory method; on the column side it's stored
 * as an {@link Types#INTEGER INTEGER}.
 *
 * <p>Phase B.5 (2026-05-29): rewritten for Hibernate 6's generic
 * {@code UserType<T>} contract. Notable changes from the Hibernate 5 form:
 * <ul>
 *   <li>{@code sqlTypes()} (an {@code int[]}) is gone — replaced by
 *       {@code getSqlType()} returning a single JDBC {@link Types} code.</li>
 *   <li>{@code nullSafeGet} / {@code nullSafeSet} take a single column
 *       {@code position} (was a string-array of column names).</li>
 *   <li>{@code EnhancedUserType}'s {@code objectToSQLString} /
 *       {@code toXMLString} / {@code fromXMLString} (string-form
 *       serialisation) no longer exists; the new interface uses
 *       {@code toString} / {@code fromStringValue} via the base
 *       {@link UserType} contract for cache disassemble — which here just
 *       reuses the enum value verbatim since enums are immutable.</li>
 *   <li>{@code ReflectHelper} (an internal API in Hibernate 5) is replaced
 *       with plain {@link Class#forName(String)}.</li>
 * </ul>
 *
 * <p>The string-based {@code @Type(type = "status")} annotation form is also
 * gone in Hibernate 6 — see the {@code StatusType} / {@code RuleContextType}
 * / {@code ActionTypeType} / {@code LoginStatusType} concrete subclasses
 * that pre-configure the enum class and let domain entities use
 * {@code @Type(StatusType.class)} directly.
 *
 * @author Krikor Krumlian (original)
 */
public class CodedEnumType implements UserType<CodedEnum>, ParameterizedType {

    private Class<? extends CodedEnum> enumClass;
    protected final Logger logger = LoggerFactory.getLogger(getClass().getName());

    /**
     * Subclass hook: a concrete type can hard-bind the enum class without
     * going through Hibernate's {@code @Parameter} machinery.
     */
    protected void setEnumClass(Class<? extends CodedEnum> enumClass) {
        this.enumClass = enumClass;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void setParameterValues(Properties parameters) {
        String enumClassName = parameters.getProperty("enumClassname");
        if (enumClassName == null) {
            // Concrete subclass has already wired the enum class via setEnumClass().
            return;
        }
        try {
            enumClass = (Class<? extends CodedEnum>) Class.forName(enumClassName);
        } catch (ClassNotFoundException cnfe) {
            throw new HibernateException("Enum class not found: " + enumClassName, cnfe);
        }
    }

    @Override
    public int getSqlType() {
        return Types.INTEGER;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Class<CodedEnum> returnedClass() {
        return (Class<CodedEnum>) enumClass;
    }

    @Override
    public boolean isMutable() {
        return false;
    }

    @Override
    public CodedEnum deepCopy(CodedEnum value) {
        return value;
    }

    @Override
    public Serializable disassemble(CodedEnum value) {
        // CodedEnum is an interface (not Serializable by declaration), even
        // though concrete implementations are enums (always Serializable).
        // Disassemble to the integer code, which is naturally Serializable.
        return value == null ? null : value.getCode();
    }

    @Override
    public CodedEnum assemble(Serializable cached, Object owner) {
        if (cached == null) {
            return null;
        }
        return getByCode(cached.toString());
    }

    @Override
    public CodedEnum replace(CodedEnum detached, CodedEnum managed, Object owner) {
        return detached;
    }

    @Override
    public boolean equals(CodedEnum x, CodedEnum y) {
        return x == y;
    }

    @Override
    public int hashCode(CodedEnum x) {
        return x == null ? 0 : x.hashCode();
    }

    @Override
    public CodedEnum nullSafeGet(ResultSet rs, int position, SharedSessionContractImplementor session, Object owner)
            throws SQLException {
        int code = rs.getInt(position);
        return rs.wasNull() ? null : getByCode(Integer.toString(code));
    }

    @Override
    public void nullSafeSet(PreparedStatement st, CodedEnum value, int index, SharedSessionContractImplementor session)
            throws SQLException {
        if (value == null) {
            st.setNull(index, Types.INTEGER);
        } else {
            Integer code = value.getCode();
            logger.debug("Binding '{}' to parameter: {}", code, index);
            st.setInt(index, code);
        }
    }

    private CodedEnum getByCode(String key) {
        Method method = null;
        Integer theKey = null;
        try {
            theKey = Integer.valueOf(key);
            method = enumClass.getMethod("getByCode", Integer.class);
            return (CodedEnum) method.invoke(null, theKey);
        } catch (NumberFormatException e) {
            throw new CodedEnumPersistenceException(
                    "Value passed in has wrong type; method=" + method + " key=" + theKey, e);
        } catch (SecurityException e) {
            throw new CodedEnumPersistenceException(
                    "SecurityException on method=" + method + " key=" + theKey, e);
        } catch (NoSuchMethodException e) {
            throw new CodedEnumPersistenceException(
                    "Method getByCode(Integer) not found on " + enumClass + "; key=" + theKey, e);
        } catch (IllegalArgumentException e) {
            throw new CodedEnumPersistenceException(
                    "Could not call method=" + method + " key=" + theKey, e);
        } catch (IllegalAccessException e) {
            throw new CodedEnumPersistenceException(
                    "No access to method=" + method + " key=" + theKey, e);
        } catch (InvocationTargetException e) {
            throw new CodedEnumPersistenceException(
                    "InvocationTargetException on method=" + method + " key=" + theKey, e);
        }
    }
}
