package com.qiniu.convert;

import com.qiniu.line.JsonStrParser;
import com.qiniu.line.SplitLineParser;
import com.qiniu.interfaces.ILineParser;
import com.qiniu.interfaces.ITypeConvert;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class LineToMap implements ITypeConvert<String, Map<String, String>> {

    private ILineParser<String> lineParser;
    private List<String> errorList = new ArrayList<>();

    public LineToMap(String parseType, String separator, HashMap<String, String> indexMap) throws IOException {
        if ("json".equals(parseType)) {
            this.lineParser = new JsonStrParser(indexMap);
        } else if ("csv".equals(parseType)) {
            this.lineParser = new SplitLineParser(",", indexMap);
        } else if ("tab".equals(parseType)) {
            this.lineParser = new SplitLineParser(separator, indexMap);
        } else {
            throw new IOException("please check your format for line to map.");
        }
    }

    public List<Map<String, String>> convertToVList(List<String> srcList) {
        if (srcList == null || srcList.size() == 0) return new ArrayList<>();
        return srcList.stream()
                .map(line -> {
                    try {
                        return lineParser.getItemMap(line);
                    } catch (Exception e) {
                        addError(line + "\t" + e.getMessage());
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    synchronized private void addError(String errorLine) {
        errorList.add(errorLine);
    }

    public List<String> getErrorList() {
        return errorList;
    }

    public List<String> consumeErrorList() {
        List<String> errors = new ArrayList<>();
        Collections.addAll(errors, new String[errorList.size()]);
        Collections.copy(errors, errorList);
        errorList.clear();
        return errors;
    }
}