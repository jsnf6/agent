package org.javaup.graphrag.dto;

import java.util.List;

/**
 * @program:
 * @description: 数据传输对象
 * @author:
 **/
public record InstructorCoursesDto(String instructor, List<String> otherCourses) {
}
