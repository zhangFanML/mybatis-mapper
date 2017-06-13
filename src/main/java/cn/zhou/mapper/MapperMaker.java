package cn.zhou.mapper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.log4j.Logger;
import org.dom4j.Attribute;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;
import org.springframework.util.ResourceUtils;

import cn.zhou.mapper.anno.Table;
import cn.zhou.mapper.dao.BaseMapper;
import cn.zhou.mapper.dao.ExampleMapper;
import cn.zhou.mapper.dao.PrimaryKeyMapper;
import cn.zhou.mapper.util.MapperConf;
import cn.zhou.mapper.util.ResMap;
import cn.zhou.mapper.xml.XMLMapperSqlProvider;

public class MapperMaker {

    private final Logger logger = Logger.getLogger(MapperMaker.class);
    private String mapperLocation;
    private String mapperMatch;
    private String replaceFrom;
    private String replaceTo;
    {
        ResourceBundle bundle = ResourceBundle.getBundle("mapper");
        mapperLocation = bundle.getString("mapper.location");
        mapperMatch = bundle.getString("mapper.match");
        replaceFrom = bundle.getString("mapper.replaceFrom");
        replaceTo = bundle.getString("mapper.replaceTo");
    }

    public void make() throws FileNotFoundException {
        File file = ResourceUtils.getFile(mapperLocation);
        List<File> fileList = collectFile(file);
        fileList.parallelStream().forEach(f -> {
            try {
                makeFile(f);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private List<File> collectFile(File file) {
        List<File> fileList = new ArrayList<>();
        if (file.isDirectory()) {
            File[] files = file.listFiles(x -> x.getName().matches(mapperMatch) || x.isDirectory());
            for (File f : files) {
                fileList.addAll(collectFile(f));
            }
        } else {
            fileList.add(file);
        }
        return fileList;
    }

    private void makeFile(File file) throws IOException, ClassNotFoundException, IllegalAccessException,
            IllegalArgumentException, InvocationTargetException, InstantiationException, DocumentException {
        String filename = file.getName().replace(replaceFrom, replaceTo);
        File newFile = new File(file.getParent(), filename);
        if (newFile.exists()) {
            logger.info(String.format("ignore file %s", newFile.getName()));
            return;
        }
        InputStream in = new FileInputStream(file);
        FileWriter out = new FileWriter(newFile);
        merge(in, out);
        out.close();
        in.close();
        logger.info(String.format("make file %s", newFile.getName()));
    }

    public void merge(InputStream inputStream, Writer writer)
            throws DocumentException, ClassNotFoundException, IllegalAccessException, IllegalArgumentException,
            InvocationTargetException, InstantiationException, IOException {
        // conf
        final MapperConf conf = new MapperConf();
        SAXReader reader = new SAXReader();
        Document doc = reader.read(inputStream);
        Element root = doc.getRootElement();
        Attribute attr = root.attribute("namespace");// mapper类名
        String ns = attr.getStringValue();
        Class<?> mapperClazz = Class.forName(ns);
        Table tableAnno = mapperClazz.getAnnotation(Table.class);// 根据注解获取表名
        if (tableAnno == null) {
            doc.write(writer);
            return;
        }
        String tablename = tableAnno.name();
        Element baseResultMap = null;
        List<Element> resultMaps = root.elements("resultMap");
        for (Element e : resultMaps) {
            if ("BaseResultMap".equals(e.attribute("id").getStringValue())) {
                baseResultMap = e;
            }
        }
        if (baseResultMap != null) {
            Element idEle = baseResultMap.element("id");
            if (idEle != null) {
                ResMap idResMap = new ResMap();
                idResMap.setColumn(idEle.attribute("column").getStringValue());
                idResMap.setJdbcType(idEle.attribute("jdbcType").getStringValue());
                idResMap.setProperty(idEle.attribute("property").getStringValue());
                conf.setIdResMap(idResMap);
            }
            List<Element> mappings = baseResultMap.elements();
            List<ResMap> resMaps = mappings.stream().map(x -> {// 从BaseResultMap获取字段映射
                ResMap resMap = new ResMap();
                resMap.setColumn(x.attribute("column").getStringValue());
                resMap.setJdbcType(x.attribute("jdbcType").getStringValue());
                resMap.setProperty(x.attribute("property").getStringValue());
                return resMap;
            }).collect(Collectors.toList());
            conf.setResMaps(resMaps);
        } else {
            throw new RuntimeException("未定义BaseResultMap");
        }
        conf.setNamespace(ns);
        conf.setTablename(tablename);
        // write
        Type[] types = mapperClazz.getGenericInterfaces();
        Map<Type, ParameterizedType> ptMap = new HashMap<>();
        for (Type t : types) {
            if (t instanceof ParameterizedType) {
                ParameterizedType pt = (ParameterizedType) t;
                ptMap.put(pt.getRawType(), pt);
            }
        }
        Set<String> methods = new HashSet<>();// 获取泛型接口的方法
        if (ptMap.get(BaseMapper.class) != null) {
            String idType = ptMap.get(BaseMapper.class).getActualTypeArguments()[2].getTypeName();
            conf.setIdType(idType);
            methods.addAll(
                    Arrays.stream(BaseMapper.class.getMethods()).map(m -> m.getName()).collect(Collectors.toList()));
        }
        if (ptMap.get(ExampleMapper.class) != null) {
            methods.addAll(
                    Arrays.stream(ExampleMapper.class.getMethods()).map(m -> m.getName()).collect(Collectors.toList()));
        }
        if (ptMap.get(PrimaryKeyMapper.class) != null) {
            methods.addAll(Arrays.stream(PrimaryKeyMapper.class.getMethods()).map(m -> m.getName())
                    .collect(Collectors.toList()));
            String idType = ptMap.get(PrimaryKeyMapper.class).getActualTypeArguments()[1].getTypeName();
            conf.setIdType(idType);
        }
        Class<XMLMapperSqlProvider> providerClazz = XMLMapperSqlProvider.class;
        XMLMapperSqlProvider provider = null;
        Method[] providerMethods = null;
        provider = providerClazz.newInstance();
        providerMethods = providerClazz.getDeclaredMethods();
        Map<String, Method> providerMethodMap = new HashMap<>();
        for (Method m : providerMethods) {
            providerMethodMap.put(m.getName(), m);
        }
        List<Element> eleList = root.elements();
        Map<String, Element> idEleMap = new HashMap<>();// mapper.xml已经配置的标签
        for (Element ele : eleList) {
            String id = ele.attribute("id").getStringValue();
            idEleMap.put(id, ele);
        }
        for (String m : methods) {// 对泛型接口每一个方法，调用provider的同名方法，生成标签，插入到xml。
            if (idEleMap.get(m) == null) {// mapper.xml配置的sql优先级高
                Method method = providerMethodMap.get(m);
                String sqlTag = (String) method.invoke(provider, conf);
                root.add(DocumentHelper.parseText(sqlTag).getRootElement());
            }
        }
        doc.write(writer);
    }
}