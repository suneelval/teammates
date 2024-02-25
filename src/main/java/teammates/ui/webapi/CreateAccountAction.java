package teammates.ui.webapi;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.http.HttpStatus;

import teammates.common.datatransfer.SqlDataBundle;
import teammates.common.exception.EntityAlreadyExistsException;
import teammates.common.exception.EntityDoesNotExistException;
import teammates.common.exception.InvalidParametersException;
import teammates.common.util.Const;
import teammates.common.util.FieldValidator;
import teammates.common.util.Logger;
import teammates.common.util.StringHelper;
import teammates.common.util.Templates;
import teammates.common.util.TimeHelper;
import teammates.sqllogic.core.DataBundleLogic;
import teammates.storage.sqlentity.AccountRequest;
import teammates.storage.sqlentity.Instructor;
import teammates.storage.sqlentity.Student;
import teammates.ui.request.InvalidHttpRequestBodyException;

/**
 * Creates a new instructor account with sample courses.
 */
public class CreateAccountAction extends Action {

    private static final Logger log = Logger.getLogger();

    @Override
    AuthType getMinAuthLevel() {
        return AuthType.LOGGED_IN;
    }

    @Override
    void checkSpecificAccessControl() {
        // Any user can create instructor account as long as the registration key is valid.
    }

    @Override
    public JsonResult execute() throws InvalidHttpRequestBodyException, InvalidOperationException {
        String registrationKey = getNonNullRequestParamValue(Const.ParamsNames.REGKEY);
        String timezone = getRequestParamValue(Const.ParamsNames.TIMEZONE);

        if (timezone == null || !FieldValidator.getInvalidityInfoForTimeZone(timezone).isEmpty()) {
            // Use default timezone instead
            timezone = Const.DEFAULT_TIME_ZONE;
        }

        AccountRequest accountRequest = sqlLogic.getAccountRequestByRegistrationKey(registrationKey);

        if (accountRequest == null) {
            throw new EntityNotFoundException("Account request with registration key "
                    + registrationKey + " could not be found");
        }

        if (accountRequest.getRegisteredAt() != null) {
            throw new InvalidOperationException("The registration key " + registrationKey + " has already been used.");
        }

        String instructorEmail = accountRequest.getEmail();
        String instructorName = accountRequest.getName();
        String instructorInstitution = accountRequest.getInstitute();
        String courseId;

        try {
            // persists sample data such as course, students, instructor and feedback sessions
            courseId = importAndPersistDemoData(instructorEmail, instructorName, instructorInstitution, timezone);
        } catch (InvalidParametersException | EntityAlreadyExistsException | EntityDoesNotExistException e) {
            // There should not be any invalid parameter here
            // EntityAlreadyExistsException should not be thrown as the generated demo course id should not exist.
            // If it is thrown, some programming error is the cause.
            log.severe("Unexpected error", e);
            return new JsonResult(e.getMessage(), HttpStatus.SC_INTERNAL_SERVER_ERROR);
        }

        List<Instructor> instructorList = sqlLogic.getInstructorsByCourse(courseId);

        assert !instructorList.isEmpty();

        assert userInfo != null && userInfo.id != null;

        try {
            // join the instructor to the course created previously
            sqlLogic.joinCourseForInstructor(userInfo.id, instructorList.get(0));
        } catch (EntityDoesNotExistException | EntityAlreadyExistsException | InvalidParametersException e) {
            // EntityDoesNotExistException should not be thrown as all entities should exist in demo course.
            // EntityAlreadyExistsException should not be thrown as updated entities should not have
            // conflict with generated entities in new demo course.
            // InvalidParametersException should not be thrown as as there should not be any invalid parameters.
            log.severe("Unexpected error", e);
            return new JsonResult(e.getMessage(), HttpStatus.SC_INTERNAL_SERVER_ERROR);
        }

        try {
            setAccountRequestAsRegistered(accountRequest, instructorEmail, instructorInstitution);
        } catch (EntityDoesNotExistException | InvalidParametersException e) {
            // EntityDoesNotExistException should not be thrown as existence of account request has been validated before.
            // InvalidParametersException should not be thrown as there should not be any invalid parameters.
            log.severe("Unexpected error", e);
            return new JsonResult(e.getMessage(), HttpStatus.SC_INTERNAL_SERVER_ERROR);
        }

        return new JsonResult("Account successfully created", HttpStatus.SC_OK);
    }

    /**
     * Abstracts the logic of updating an account request to be registered.
     *
     * @return the updated account request
     */
    private AccountRequest setAccountRequestAsRegistered(AccountRequest accountRequest,
            String instructorEmail, String instructorInstitution)
            throws InvalidParametersException, EntityDoesNotExistException {
        accountRequest.setEmail(instructorEmail);
        accountRequest.setInstitute(instructorInstitution);
        accountRequest.setRegisteredAt(Instant.now());

        sqlLogic.updateAccountRequest(accountRequest);
        return accountRequest;
    }

    private static String getDateString(Instant instant) {
        return TimeHelper.formatInstant(instant, Const.DEFAULT_TIME_ZONE, "yyyy-MM-dd");
    }

    /**
     * Imports demo course for the new instructor.
     *
     * @return the ID of demo course, which does not previously exist in the database for another course.
     * @throws EntityAlreadyExistsException if the generated demo course ID already exists in the database.
     *         However, this should never occur and hence should be handled as a programmatic error.
     */
    private String importAndPersistDemoData(String instructorEmail, String instructorName,
            String instructorInstitute, String timezone)
            throws InvalidParametersException, EntityAlreadyExistsException, EntityDoesNotExistException {

        String courseId = generateDemoCourseId(instructorEmail);
        Instant now = Instant.now();

        // Used for start time + visible time for all sessions
        String dateString1 = getDateString(now.minus(7, ChronoUnit.DAYS));
        // Used for end time for sessions already past
        String dateString2 = getDateString(now.minus(3, ChronoUnit.DAYS));
        // Used for result visible time for sessions already past
        String dateString3 = getDateString(now.minus(2, ChronoUnit.DAYS));
        // Used for end time for session still ongoing
        String dateString4 = getDateString(now.plus(3, ChronoUnit.DAYS));
        // Used for timestamp of comments
        String dateString5 = getDateString(now);

        String dataBundleString = Templates.populateTemplate(Templates.INSTRUCTOR_SAMPLE_DATA,
                // replace email
                "teammates.demo.instructor@demo.course", instructorEmail,
                // replace name
                "Demo_Instructor", instructorName,
                // replace course
                "demo.course", courseId,
                // replace institute
                "demo.institute", instructorInstitute,
                // replace timezone
                "demo.timezone", timezone,
                // replace dates
                "demo.date1", dateString1,
                "demo.date2", dateString2,
                "demo.date3", dateString3,
                "demo.date4", dateString4,
                "demo.date5", dateString5
                );

        if (!Const.DEFAULT_TIME_ZONE.equals(timezone)) {
            dataBundleString = replaceAdjustedTimeAndTimezone(dataBundleString, timezone);
        }

        SqlDataBundle sqlDataBundle = DataBundleLogic.deserializeDataBundle(dataBundleString);

        sqlLogic.persistDataBundle(sqlDataBundle);

        List<Student> students = sqlLogic.getStudentsForCourse(courseId);
        List<Instructor> instructors = sqlLogic.getInstructorsByCourse(courseId);

        for (Student student : students) {
            taskQueuer.scheduleStudentForSearchIndexing(student.getCourse().getId(), student.getEmail());
        }

        for (Instructor instructor : instructors) {
            taskQueuer.scheduleInstructorForSearchIndexing(instructor.getCourseId(), instructor.getEmail());
        }

        return courseId;
    }

    // Strategy to Generate New Demo Course Id:
    // a. keep the part of email before "@"
    //    replace "@" with "."
    //    replace email host with their first 3 chars. eg, gmail.com -> gma
    //    append "-demo"
    //    to sum up: lebron@gmail.com -> lebron.gma-demo
    //
    // b. if the generated courseId already exists, create another one by appending a integer to the previous courseId.
    //    if the newly generate id still exists, increment the id, until we find a feasible one
    //    eg.
    //    lebron@gmail.com -> lebron.gma-demo  // already exists!
    //    lebron@gmail.com -> lebron.gma-demo0 // already exists!
    //    lebron@gmail.com -> lebron.gma-demo1 // already exists!
    //    ...
    //    lebron@gmail.com -> lebron.gma-demo99 // already exists!
    //    lebron@gmail.com -> lebron.gma-demo100 // found! a feasible id
    //
    // c. in any cases(a or b), if generated Id is longer than FieldValidator.COURSE_ID_MAX_LENGTH, shorten the part
    //    before "@" of the initial input email, by continuously removing its last character

    /**
     * Generate a brand new previously unused course ID for demo course.
     * Works by generating a course id until it finds one that is not being used by another course in the database.
     *
     * @param instructorEmail is the instructor email.
     * @return generated course id that is new and not used previously.
     */
    private String generateDemoCourseId(String instructorEmail) {
        String proposedCourseId = generateNextDemoCourseId(instructorEmail, FieldValidator.COURSE_ID_MAX_LENGTH);

        while (sqlLogic.getCourse(proposedCourseId) != null) {
            proposedCourseId = generateNextDemoCourseId(proposedCourseId, FieldValidator.COURSE_ID_MAX_LENGTH);
        }
        return proposedCourseId;
    }

    /**
     * Generate a course ID for demo course from a given email.
     *
     * @param instructorEmail is the instructor email.
     * @return the first proposed course id. eg.lebron@gmail.com -> lebron.gma-demo
     */
    private String getDemoCourseIdRoot(String instructorEmail) {
        String[] emailSplit = instructorEmail.split("@");

        String username = emailSplit[0];
        String host = emailSplit[1];

        String head = StringHelper.replaceIllegalChars(username, FieldValidator.REGEX_COURSE_ID, '_');
        String hostAbbreviation = host.substring(0, Math.min(host.length(), 3));

        return head + "." + hostAbbreviation + "-demo";
    }

    /**
     * Generate a course ID for demo course from a given email or a generated course Id.
     *
     * <p>Here we check the input string is an email or course Id and handle them accordingly;
     * check the resulting course id, and if bigger than maximumIdLength, cut it so that it equals maximumIdLength.
     *
     * @param instructorEmailOrProposedCourseId is the instructor email or a proposed course id that already exists.
     * @param maximumIdLength is the maximum resulting id length allowed, above which we will cut the part before "@"
     * @return the proposed course id, e.g.:
     *         <ul>
     *         <li>lebron@gmail.com -> lebron.gma-demo</li>
     *         <li>lebron.gma-demo -> lebron.gma-demo0</li>
     *         <li>lebron.gma-demo0 -> lebron.gma-demo1</li>
     *         <li>012345678901234567890123456789.gma-demo9 -> 01234567890123456789012345678.gma-demo10 (being cut)</li>
     *         </ul>
     */
    public String generateNextDemoCourseId(String instructorEmailOrProposedCourseId, int maximumIdLength) {
        boolean isFirstCourseId = instructorEmailOrProposedCourseId.contains("@");
        if (isFirstCourseId) {
            return StringHelper.truncateHead(getDemoCourseIdRoot(instructorEmailOrProposedCourseId), maximumIdLength);
        }

        boolean isFirstTimeDuplicate = instructorEmailOrProposedCourseId.endsWith("-demo");
        if (isFirstTimeDuplicate) {
            return StringHelper.truncateHead(instructorEmailOrProposedCourseId + "0", maximumIdLength);
        }

        int lastIndexOfDemo = instructorEmailOrProposedCourseId.lastIndexOf("-demo");
        String root = instructorEmailOrProposedCourseId.substring(0, lastIndexOfDemo);
        int previousDedupSuffix = Integer.parseInt(instructorEmailOrProposedCourseId.substring(lastIndexOfDemo + 5));

        return StringHelper.truncateHead(root + "-demo" + (previousDedupSuffix + 1), maximumIdLength);
    }

    /**
     * Replace time and timezone based on users timezone.
     * Strings representing instant are adjusted so that they represent the same date and time but in the users timezone.
     * Timezone is changed to users timezone.
     */
    private String replaceAdjustedTimeAndTimezone(String template, String timezoneString) {
        // timezoneString should have been validated in #execute() method already
        assert ZoneId.getAvailableZoneIds().contains(timezoneString);

        String pattern = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z"; // regex for instant
        ZoneId timezone = ZoneId.of(timezoneString);

        // replace instant with instant adjusted for user's timezone
        return Pattern.compile(pattern).matcher(template).replaceAll(timestampMatch -> {
            String timestamp = timestampMatch.group();
            Instant instant = Instant.parse(timestamp);

            if (TimeHelper.isSpecialTime(instant)) {
                return timestamp;
            }

            return ZonedDateTime.ofInstant(instant, ZoneId.of(Const.DEFAULT_TIME_ZONE))
                    .withZoneSameLocal(timezone).toInstant().toString();
        });
    }
}
