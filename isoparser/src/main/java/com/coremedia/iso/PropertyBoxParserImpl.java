package com.coremedia.iso;

import com.coremedia.iso.boxes.AbstractBox;
import com.coremedia.iso.boxes.Box;
import com.coremedia.iso.boxes.fragment.MovieFragmentBox;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.util.Enumeration;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Property file based BoxFactory
 */
public class PropertyBoxParserImpl extends AbstractBoxParser {
    Properties mapping;

    public PropertyBoxParserImpl(String... customProperties) {
        InputStream is = new BufferedInputStream(getClass().getResourceAsStream("/isoparser-default.properties"));
        try {
            mapping = new Properties();
            try {
                mapping.load(is);
                Enumeration<URL> enumeration = Thread.currentThread().getContextClassLoader().getResources("isoparser-custom.properties");

                while (enumeration.hasMoreElements()) {
                    URL url = enumeration.nextElement();
                    InputStream customIS = new BufferedInputStream(url.openStream());
                    try {
                        mapping.load(customIS);
                    } finally {
                        customIS.close();
                    }
                }
                for (String customProperty : customProperties) {
                    mapping.load(new BufferedInputStream(getClass().getResourceAsStream(customProperty)));
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } finally {
            try {
                is.close();
            } catch (IOException e) {
                e.printStackTrace();
                // ignore - I can't help
            }
        }
    }

    public PropertyBoxParserImpl(Properties mapping) {
        this.mapping = mapping;
    }

    Pattern p = Pattern.compile("(.*)\\((.*?)\\)");

    @SuppressWarnings("unchecked")
    public Class<? extends Box> getClassForFourCc(byte[] type, byte[] userType, byte[] parent) {
        FourCcToBox fourCcToBox = new FourCcToBox(type, userType, parent).invoke();
        try {
            return (Class<? extends Box>) Class.forName(fourCcToBox.clazzName);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public AbstractBox createBox(byte[] type, byte[] userType, byte[] parent) {

        FourCcToBox fourCcToBox = new FourCcToBox(type, userType, parent).invoke();
        String[] param = fourCcToBox.getParam();
        String clazzName = fourCcToBox.getClazzName();
        try {
            if (param[0].trim().length() == 0) {
                param = new String[]{};
            }
            Class clazz = Class.forName(clazzName);

            Class[] constructorArgsClazz = new Class[param.length];
            Object[] constructorArgs = new Object[param.length];
            for (int i = 0; i < param.length; i++) {

                if ("userType".equals(param[i])) {
                    constructorArgs[i] = userType;
                    constructorArgsClazz[i] = byte[].class;
                } else if ("type".equals(param[i])) {
                    constructorArgs[i] = type;
                    constructorArgsClazz[i] = byte[].class;
                } else if ("parent".equals(param[i])) {
                    constructorArgs[i] = parent;
                    constructorArgsClazz[i] = byte[].class;
                } else {
                    throw new InternalError("No such param: " + param[i]);
                }


            }
            Constructor<AbstractBox> constructorObject;
            try {
                if (param.length > 0) {
                    constructorObject = clazz.getConstructor(constructorArgsClazz);
                } else {
                    constructorObject = clazz.getConstructor();
                }

                return constructorObject.newInstance(constructorArgs);
            } catch (NoSuchMethodException e) {
                throw new RuntimeException(e);
            } catch (InvocationTargetException e) {
                throw new RuntimeException(e);
            } catch (InstantiationException e) {
                throw new RuntimeException(e);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }


        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    private class FourCcToBox {
        private byte[] type;
        private byte[] userType;
        private byte[] parent;
        private String clazzName;
        private String[] param;

        public FourCcToBox(byte[] type, byte[] userType, byte[] parent) {
            this.type = type;
            this.parent = parent;
            this.userType = userType;
        }

        public String getClazzName() {
            return clazzName;
        }

        public String[] getParam() {
            return param;
        }

        public FourCcToBox invoke() {
            String constructor;
            if (userType != null) {
                if (!"uuid".equals(IsoFile.bytesToFourCC(type))) {
                    throw new RuntimeException("we have a userType but no uuid box type. Something's wrong");
                }
                constructor = mapping.getProperty(IsoFile.bytesToFourCC(parent) + "-uuid[" + Hex.encodeHex(userType).toUpperCase() + "]");
                if (constructor == null) {
                    constructor = mapping.getProperty("uuid[" + Hex.encodeHex(userType).toUpperCase() + "]");
                }
                if (constructor == null) {
                    constructor = mapping.getProperty("uuid");
                }
            } else {
                constructor = mapping.getProperty(IsoFile.bytesToFourCC(parent) + "-" + IsoFile.bytesToFourCC(type));
                if (constructor == null) {
                    constructor = mapping.getProperty(IsoFile.bytesToFourCC(type));
                }
            }
            if (constructor == null) {
                constructor = mapping.getProperty("default");
            }
            if (constructor == null) {
                throw new RuntimeException("No box object found for " + IsoFile.bytesToFourCC(type));
            }
            Matcher m = p.matcher(constructor);
            boolean matches = m.matches();
            if (!matches) {
                throw new RuntimeException("Cannot work with that constructor: " + constructor);
            }
            clazzName = m.group(1);
            param = m.group(2).split(",");
            return this;
        }
    }
}
