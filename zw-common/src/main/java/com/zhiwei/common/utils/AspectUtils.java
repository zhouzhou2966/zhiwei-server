package com.zhiwei.common.utils;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;

import java.lang.reflect.Method;

public class AspectUtils {

    /**
     * 获取被拦截方法对象
     * MethodSignature.getMethod() 获取的是顶层接口或者父类的方法对象
     * 所以应该使用反射获取当前对象的方法对象
     */
    public static Method getMethod(ProceedingJoinPoint pjp) {
        //获取参数的类型
        Signature sig = pjp.getSignature();
        if (sig instanceof MethodSignature) {
            MethodSignature methodSignature = (MethodSignature) sig;
            return methodSignature.getMethod();
        } else {
            throw new IllegalArgumentException("It's not method");
        }
    }

    /**
     * 在aop切面中SPEL表达式对formatter进行格式化，
     * 转换出指定的值
     *
     * @param formatter
     * @param method
     * @param args
     * @return
     */
    public static String parse(String formatter, Method method, Object[] args) {
        // Spring 6.1 移除了 LocalVariableTableParameterNameDiscoverer（它依赖字节码调试信息），
        // 统一改用 DefaultParameterNameDiscoverer：优先取编译期 -parameters 留下的真实参数名，
        // Spring Boot 父 POM 默认已开启 -parameters，所以这里拿到的参数名与原来一致。
        ParameterNameDiscoverer nameDiscoverer = new DefaultParameterNameDiscoverer();
        return SPELUtils.parse(formatter, nameDiscoverer.getParameterNames(method), args);
    }
}
