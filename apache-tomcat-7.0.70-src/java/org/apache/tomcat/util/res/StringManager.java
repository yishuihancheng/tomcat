/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.tomcat.util.res;

import org.apache.catalina.startup.Catalina;
import sun.reflect.Reflection;

import java.text.MessageFormat;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * An internationalization / localization helper class which reduces
 * the bother of handling ResourceBundles and takes care of the
 * common cases of message formating which otherwise require the
 * creation of Object arrays and such.
 *
 * 这个类存储了所有包的StringManage 用 managers存储，
 * 存储的数据结构Map<String, Map<Local, StringManager>>存储，与ThreadLocal 有异曲同工之妙
 *
 * 可以参见http://www.cnblogs.com/chenying99/archive/2012/09/05/2671204.html
 *
 * <p>The StringManager operates on a package basis. One StringManager
 * per package can be created and accessed via the getManager method
 * call.
 *
 * <p>The StringManager will look for a ResourceBundle named by
 * the package name given plus the suffix of "LocalStrings". In
 * practice, this means that the localized information will be contained
 * in a LocalStrings.properties file located in the package
 * directory of the class path.
 *
 * <p>Please see the documentation for java.util.ResourceBundle for
 * more information.
 *
 * @author James Duncan Davidson [duncan@eng.sun.com]
 * @author James Todd [gonzo@eng.sun.com]
 * @author Mel Martinez [mmartinez@g1440.com]
 * @see java.util.ResourceBundle
 */
public class StringManager {

    private static int LOCALE_CACHE_SIZE = 10;

    /**
     * The ResourceBundle for this StringManager.
     */
    private final ResourceBundle bundle;
    private final Locale locale;


    /**
     * Creates a new StringManager for a given package. This is a
     * private method and all access to it is arbitrated by the
     * static getManager method call so that only one StringManager
     * per package will be created.
     *
     * 正常情况下是使用调用者的classLoader
     * 如果出现异常使用contextClassLoader，是系统默认的类装载器？也就是ApplicationSystemClassLoader
     * 默认的ClassLoader的类装载器都是App装载器，在URLClassLoader里面设置的装载器parent 默认是AppClassLoader
     *
     * @param packageName Name of package to create StringManager for.
     */
    private StringManager(String packageName, Locale locale) {
        /**
         * 测试代码，测试classLoader 和 getContextClassLoader
         */
        // Reflection.getCallerClass(1)当前方法所在类的信息
//        System.out.println(Reflection.getCallerClass().toString());
        System.out.println(getClass().getClassLoader().toString());
        System.out.println(String.class.getClassLoader());
        System.out.println(Thread.currentThread().getContextClassLoader().toString());

        String bundleName = packageName + ".LocalStrings";
        ResourceBundle bnd = null;
        try {
            bnd = ResourceBundle.getBundle(bundleName, locale);
        } catch (MissingResourceException ex) {
            // Try from the current loader (that's the case for trusted apps)
            // Should only be required if using a TC5 style classloader structure
            // where common != shared != server
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl != null) {
                try {
                    bnd = ResourceBundle.getBundle(bundleName, locale, cl);
                } catch (MissingResourceException ex2) {
                    // Ignore
                }
            }
        }
        bundle = bnd;
        // Get the actual locale, which may be different from the requested one
        if (bundle != null) {
            Locale bundleLocale = bundle.getLocale();
            if (bundleLocale.equals(Locale.ROOT)) {
                this.locale = Locale.ENGLISH;
            } else {
                this.locale = bundleLocale;
            }
        } else {
            this.locale = null;
        }
    }


    /**
     * Get a string from the underlying resource bundle or return null if the
     * String is not found.
     *
     * @param key to desired resource String
     *
     * @return resource String matching <i>key</i> from underlying bundle or
     *         null if not found.
     *
     * @throws IllegalArgumentException if <i>key</i> is null
     */
    public String getString(String key) {
        if (key == null){
            String msg = "key may not have a null value";
            throw new IllegalArgumentException(msg);
        }

        String str = null;

        try {
            // Avoid NPE if bundle is null and treat it like an MRE
            if (bundle != null) {
                str = bundle.getString(key);
            }
        } catch (MissingResourceException mre) {
            //bad: shouldn't mask an exception the following way:
            //   str = "[cannot find message associated with key '" + key +
            //         "' due to " + mre + "]";
            //     because it hides the fact that the String was missing
            //     from the calling code.
            //good: could just throw the exception (or wrap it in another)
            //      but that would probably cause much havoc on existing
            //      code.
            //better: consistent with container pattern to
            //      simply return null.  Calling code can then do
            //      a null check.
            str = null;
        }

        return str;
    }


    /**
     * Get a string from the underlying resource bundle and format
     * it with the given set of arguments.
     *
     * @param key  The key for the required message
     * @param args The values to insert into the message
     *
     * @return The request string formatted with the provided arguments or the
     *         key if the key was not found.
     */
    public String getString(final String key, final Object... args) {
        String value = getString(key);
        if (value == null) {
            value = key;
        }

        MessageFormat mf = new MessageFormat(value);
        mf.setLocale(locale);
        return mf.format(args, new StringBuffer(), null).toString();
    }


    /**
     * Identify the Locale this StringManager is associated with.
     *
     * @return The Locale associated with the StringManager
     */
    public Locale getLocale() {
        return locale;
    }


    // --------------------------------------------------------------
    // STATIC SUPPORT METHODS
    // --------------------------------------------------------------

    private static final Map<String, Map<Locale,StringManager>> managers =
        new Hashtable<String, Map<Locale,StringManager>>();


    /**
     * Get the StringManager for a given class. The StringManager will be
     * returned for the package in which the class is located. If a manager for
     * that package already exists, it will be reused, else a new
     * StringManager will be created and returned.
     *
     * @param clazz The class for which to retrieve the StringManager
     *
     * @return The instance associated with the package of the provide class
     */
    public static final StringManager getManager(Class<?> clazz) {
        return getManager(clazz.getPackage().getName());
    }


    /**
     * Get the StringManager for a particular package. If a manager for
     * a package already exists, it will be reused, else a new
     * StringManager will be created and returned.
     *
     * @param packageName The package name
     *
     * @return The instance associated with the given package and the default
     *         Locale
     */
    public static final StringManager getManager(String packageName) {
        return getManager(packageName, Locale.getDefault());
    }


    /**
     * Get the StringManager for a particular package and Locale. If a manager
     * for a package/Locale combination already exists, it will be reused, else
     * a new StringManager will be created and returned.
     * 获取包的manager 这个manager是从一个map读取到的
     * 此map为managers key值为包名，value为 Map<Locale,StringManager>
     * 如果有了 直接创建，如果没有，那么服用
     *
     * 对于每个包的StringManager， 使用一个map存着数据
     * 这个数据结构为LinkedHashMap，最大容量为LOCALE_CACHE_SIZE，
     * 当插入一个数据后，要进行检查，如果是最大容量，则删除一条记录，重写了removeEldestEntry
     *
     * @param packageName The package name
     * @param locale      The Locale
     *
     * @return The instance associated with the given package and Locale
     */
    public static final synchronized StringManager getManager(
            String packageName, Locale locale) {

        Map<Locale,StringManager> map = managers.get(packageName);
        if (map == null) {
            /*
             * Don't want the HashMap to be expanded beyond LOCALE_CACHE_SIZE.
             * Expansion occurs when size() exceeds capacity. Therefore keep
             * size at or below capacity.
             * removeEldestEntry() executes after insertion therefore the test
             * for removal needs to use one less than the maximum desired size
             *
             */
            map = new LinkedHashMap<Locale,StringManager>(LOCALE_CACHE_SIZE, 1, true) {
                private static final long serialVersionUID = 1L;
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<Locale,StringManager> eldest) {
                    if (size() > (LOCALE_CACHE_SIZE - 1)) {
                        return true;
                    }
                    return false;
                }
            };
            managers.put(packageName, map);
        }

        StringManager mgr = map.get(locale);
        if (mgr == null) {
            mgr = new StringManager(packageName, locale);
            map.put(locale, mgr);
        }
        return mgr;
    }


    /**
     * Retrieve the StringManager for a list of Locales. The first StringManager
     * found will be returned.
     *
     * @param packageName      The package for which the StringManager was
     *                         requested
     * @param requestedLocales The list of Locales
     *
     * @return the found StringManager or the default StringManager
     */
    public static StringManager getManager(String packageName,
            Enumeration<Locale> requestedLocales) {
        while (requestedLocales.hasMoreElements()) {
            Locale locale = requestedLocales.nextElement();
            StringManager result = getManager(packageName, locale);
            if (result.getLocale().equals(locale)) {
                return result;
            }
        }
        // Return the default
        return getManager(packageName);
    }
}
