package cn.zhou.mapper.plugin;

import java.lang.reflect.Method;
import java.util.Properties;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import cn.zhou.mapper.util.ReflectHelper;

@Intercepts({
        @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class,
                RowBounds.class, ResultHandler.class }),
        @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class }) })

public class ExecutorPlugin implements Interceptor {

    public Object intercept(Invocation invocation) throws Throwable {
        Object[] args = invocation.getArgs();
        MappedStatement statement = (MappedStatement) args[0];
        Configuration configuration = statement.getConfiguration();
        Object parameterObject = args[1];
        statement.getSqlSource().getBoundSql(parameterObject);
        String sql = statement.getBoundSql(parameterObject).getSql();
        if ("base.selectByExample".equals(sql)) {
            String script = // 不支持写<selectKey>，不支持<include>
                    "select * from sys_user <where> 1=1</where>order by #{orderByClause}</script>";
            SqlSource dynamicSqlSource = new XMLLanguageDriver().createSqlSource(configuration, script,
                    parameterObject.getClass());
            ReflectHelper.setValueByFieldName(statement, "sqlSource", dynamicSqlSource);
        }
        return invocation.proceed();
    }

    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    public void setProperties(Properties properties) {
        System.out.println(properties);
    }
}