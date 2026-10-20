import request from './request'

/** 校园业务接口（资源 / 馆区 / 场馆 / 讲座 / 课程） */

/** 馆区列表（15 个） */
export function getLibraryAreas() {
  return request.get('/library/areas')
}

/** 场馆列表 */
export function getVenues(params) {
  return request.get('/venue/list', { params })
}

/** 讲座列表（含已报名人数） */
export function getLectures() {
  return request.get('/lecture/list')
}

/** 讲座报名（人数限额制，无选座；开始前 7 天开放） */
export function signupLecture(id) {
  return request.post(`/lecture/${id}/signup`)
}

/** 课程列表（分页 + 学院/类型/关键字筛选） */
export function getCourses(params) {
  return request.get('/course/list', { params })
}

/** 某课程的教学班列表（含我的选课状态标记） */
export function getCourseClasses(courseId) {
  return request.get(`/course/${courseId}/classes`)
}

/** 选课（人数限额，满员直接拒绝；自动查时间冲突） */
export function selectCourse(classId) {
  return request.post('/course/select', { classId })
}

/** 退课（释放名额，可重选） */
export function dropCourse(classId) {
  return request.post('/course/drop', { classId })
}

/** 我的课表（已选课程，按周几+节次排序） */
export function getMyCourses() {
  return request.get('/course/my')
}

/** 统一资源列表（按类型/校区/关键字） */
export function getResources(params) {
  return request.get('/resource/list', { params })
}

/** 某资源某天的时间片 */
export function getResourceSlots(resourceId, date) {
  return request.get(`/resource/${resourceId}/slots`, { params: { date } })
}

/** 工作台概览统计 */
export function getOverviewStats() {
  return request.get('/resource/stats/overview')
}
