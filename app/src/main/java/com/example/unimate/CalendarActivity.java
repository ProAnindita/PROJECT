package com.example.unimate;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.applandeo.materialcalendarview.CalendarView;
import com.applandeo.materialcalendarview.EventDay;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class CalendarActivity extends AppCompatActivity {
    private static final String TAG = "CalendarActivity";
    private TasksAdapter tasksAdapter;
    private CalendarView calendarView;
    private RecyclerView classRecycler;
    private Spinner batchSpinner, sectionSpinner;

    private FirebaseFirestore db;
    private String selectedBatch = "64";   // default
    private String selectedSection = "B";  // default

    // This map will link a specific Date -> list of ClassWithTasks (for that day).
    // We'll fill this for the next 30 days from "today".
    private final Map<Date, List<ClassWithTasks>> classTaskMap = new HashMap<>();
    private Date currentSelectedDate;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_calendar);

        db = FirebaseFirestore.getInstance();

        // Initialize UI
        calendarView = findViewById(R.id.calendarView);
        classRecycler = findViewById(R.id.classRecycler);
        classRecycler.setLayoutManager(new LinearLayoutManager(this));

        batchSpinner = findViewById(R.id.batchSpinner);
        sectionSpinner = findViewById(R.id.sectionSpinner);

        setupSpinners();
        setupCalendar();

        // Load schedules/tasks when we first open
        loadAllDataForRange();

    }


    private Date stripTime(Date date) {
        Calendar c = Calendar.getInstance();
        c.setTime(date);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTime();
    }

    /**
     * Setup the batch & section spinners
     */
    private void setupSpinners() {
        // Batch Spinner
        ArrayAdapter<CharSequence> batchAdapter = ArrayAdapter.createFromResource(
                this,
                R.array.batches_array, // e.g. ["56","57","58","59","60","61","62","63","64","65"]
                R.layout.spinner_item
        );
        batchSpinner.setAdapter(batchAdapter);

        batchSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedBatch = parent.getItemAtPosition(position).toString();
                // Reload schedules/tasks whenever batch changes
                loadAllDataForRange();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // Section Spinner
        ArrayAdapter<CharSequence> sectionAdapter = ArrayAdapter.createFromResource(
                this,
                R.array.sections_array, // e.g. ["A","B","C","D","E","F"]
                R.layout.spinner_item
        );
        sectionSpinner.setAdapter(sectionAdapter);

        sectionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedSection = parent.getItemAtPosition(position).toString();
                // Reload schedules/tasks whenever section changes
                loadAllDataForRange();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    /**
     * Set up the calendar click listener
     */
    private void setupCalendar() {
        // When user clicks a date on the calendar
        calendarView.setOnDayClickListener(eventDay -> {
            Date clicked = eventDay.getCalendar().getTime();
            Date normalized = stripTime(clicked);
            currentSelectedDate = normalized;
            calendarView.setDate(normalized);
            displayClassesForDate(normalized);

            // Highlight the clicked date on the calendar
            calendarView.setDate(currentSelectedDate);
        });
    }

    /**
     * This method loads the class schedules for the next 30 days (including today),
     * plus all tasks for that same range.
     */
    private void loadAllDataForRange() {
        // Step 1: Clear old data
        classTaskMap.clear();
        currentSelectedDate = null;

        // Step 2: Build a list of all dates from today up to 30 days in the future
        List<Date> dateList = new ArrayList<>();
        Calendar cal = Calendar.getInstance(); // start at "today"

        for (int i = 0; i < 60; i++) {
            Date dayDate = stripTime(cal.getTime());
            dateList.add(dayDate);
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }

        // Step 3: For each date, load the schedule doc named "sunday", "monday", etc.,
        //         then store the classes in classTaskMap keyed by that date.
        //         We'll do it asynchronously, but we won't wait for each one to finish
        //         to keep it simpler. Instead, we collect them all and update after.

        for (Date dayDate : dateList) {
            String dayName = getDayName(dayDate).toLowerCase();
            // e.g. "sunday", "monday", etc.

            db.collection("schedules")
                    .document(dayName)
                    .get()
                    .addOnSuccessListener(doc -> {
                        Date normalized = stripTime(dayDate);
                        if (doc.exists() && doc.getData() != null) {
                            parseClassData(doc.getData(), normalized);
                        } else {
                            classTaskMap.put(normalized, new ArrayList<>());
                            updateCalendarAppearance();
                        }
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Error loading schedule for " + dayName, e);
                        classTaskMap.put(dayDate, new ArrayList<>());
                        updateCalendarAppearance();
                    });
        }

        // Step 4: Load tasks for the entire 30-day range. Because these are Firestore async,
        //         it might arrive at any time. We'll do it once, not 30 times.

        Date startDate = dateList.get(0);                   // today
        Date endDate = dateList.get(dateList.size() - 1);
        Log.d(TAG, "Querying tasks from " + startDate + " to " + endDate);// 30 days from now

        db.collection("tasks")
                .whereEqualTo("batch", selectedBatch)
                .whereEqualTo("section", selectedSection)
                .whereGreaterThanOrEqualTo("date", startDate)
                .whereLessThanOrEqualTo("date", endDate)
                .get()
                .addOnSuccessListener(querySnapshot -> {
                    Log.d(TAG, "Found " + querySnapshot.size() + " task(s).");
                    for (QueryDocumentSnapshot doc : querySnapshot) {
                        UniTask task = doc.toObject(UniTask.class);
                        Log.d(TAG, "Task date=" + task.getDate() + ", title=" + task.getTaskTitle());

                        attachTaskToClass(task);
                    }
                    updateCalendarAppearance();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Error loading tasks", e);
                });
    }

    /**
     * Convert the Firestore data (nested map) into ClassWithTasks for a specific date
     * and store them in classTaskMap.
     */
    private void parseClassData(Map<String, Object> dayData, Date dayDate) {
        // We'll store classes for "thisDate"
        Date normalized = stripTime(dayDate);

        List<ClassWithTasks> classList = new ArrayList<>();

        String batchKey = "batch_" + selectedBatch;
        if (dayData.containsKey(batchKey)) {
            Object batchObj = dayData.get(batchKey);
            if (batchObj instanceof Map) {
                Map<String, Object> batchMap = (Map<String, Object>) batchObj;

                if (batchMap.containsKey(selectedSection)) {
                    Object sectionObj = batchMap.get(selectedSection);
                    if (sectionObj instanceof Map) {
                        Map<String, Object> timeslotMap = (Map<String, Object>) sectionObj;
                        for (Map.Entry<String, Object> entry : timeslotMap.entrySet()) {
                            String timeSlot = entry.getKey();
                            Object slotVal = entry.getValue();
                            if (slotVal instanceof Map) {
                                Map<String, Object> slotData = (Map<String, Object>) slotVal;

                                String course     = safeGetString(slotData, "course");
                                String instructor = safeGetString(slotData, "instructor");
                                String room       = safeGetString(slotData, "room");

                                ClassWithTasks aClass = new ClassWithTasks(
                                        timeSlot, course, instructor, room
                                );
                                classList.add(aClass);
                            }
                        }
                    }
                }
            }
        }

        // Put it in our map
        classTaskMap.put(normalized, classList);
        updateCalendarAppearance();
    }

    /**
     * Attach a UniTask to the matching class/time for the correct date in classTaskMap.
     */
    private void attachTaskToClass(UniTask task) {
        if (task.getDate() == null) {
            Log.d(TAG, "Task date is null, skipping");
            return;
        }
        Date taskDay = stripTime(task.getDate());

        Log.d(TAG, "Attaching task => " + task.getTaskTitle()
                + ", date=" + taskDay
                + ", timeSlot=" + task.getClassTime());

        List<ClassWithTasks> dayClasses = classTaskMap.get(taskDay);
        if (dayClasses != null) {
            boolean attached = false;
            for (ClassWithTasks c : dayClasses) {
                if (c.getTimeSlot().equals(task.getClassTime())) {
                    c.addTask(task);
                    attached = true;
                    break;
                }
            }
            Log.d(TAG, "Attached? " + attached);
        } else {
            Log.d(TAG, "No classes found for date="+taskDay);
            // If there's no class list for that date, we can create an "empty" class
            // or skip. Here we skip.
            // (If you want tasks to exist even if there's no class, you can handle it differently.)
        }
    }

    /**
     * Update the calendar dots or day colors based on whether that day has tasks.
     * If a day has at least one task, we color it orange; otherwise, green if it has classes.
     * If it has no classes at all, we won’t add an event (or you can pick a color).
     */
    private void updateCalendarAppearance() {
        List<EventDay> events = new ArrayList<>();

        for (Map.Entry<Date, List<ClassWithTasks>> entry : classTaskMap.entrySet()) {
            Date date = entry.getKey();
            List<ClassWithTasks> classes = entry.getValue();

            if (classes == null || classes.isEmpty()) {
                // No classes => no dot (or choose a color if you want a “No Class” marker)
                continue;
            }

            // If any class has tasks, mark day as orange; else green
            boolean hasAnyTask = false;
            for (ClassWithTasks c : classes) {
                if (c.hasTasks()) {
                    hasAnyTask = true;
                    break;
                }
            }

            // Convert date -> Calendar
            Calendar cal = Calendar.getInstance();
            cal.setTime(date);

            int color = hasAnyTask ? R.color.taskOrange : R.color.green_button_color;
            events.add(new EventDay(cal, color));
        }

        // Now set these events on the calendar
        calendarView.setEvents(events);

        // If the user clicked a date before we reloaded everything, refresh that day’s classes
        if (currentSelectedDate != null) {
            displayClassesForDate(currentSelectedDate);
        }
    }

    /**
     * When the user picks a day on the calendar, show the classes for that day in the RecyclerView.
     */
    private void displayClassesForDate(Date date) {
        Date stripped = stripTime(date);
        List<ClassWithTasks> classes = classTaskMap.getOrDefault(stripped, new ArrayList<>());

        if (classes.isEmpty()) {
            showToast("No classes for selected date");
        }

        // This adapter is for CLASSES, not tasks
        ClassAdapter classAdapter = new ClassAdapter(classes, classItem -> {
            if (classItem.hasTasks()) {
                // They already have tasks → show the tasks dialog
                showTaskDetails(classItem);
            } else {
                // No tasks → we can still create a TasksAdapter for adding tasks
                TasksAdapter tasksAdapter = new TasksAdapter(classItem.getTasks(), taskToRemove -> {
                    removeTaskFromFirestore(taskToRemove, classItem);
                });
                showAddTaskDialog(classItem, tasksAdapter);
            }
        });

        classRecycler.setAdapter(classAdapter);
        classAdapter.notifyDataSetChanged();
    }


    /**
     * Shows a simple dialog with tasks or just the count.
     */
    private void showTaskDetails(ClassWithTasks classItem) {
        // We want to show a dialog with all tasks for this class/time.
        showTasksDialog(classItem);
    }

    private void showTasksDialog(ClassWithTasks classItem) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_show_tasks, null);



        RecyclerView tasksRecycler = dialogView.findViewById(R.id.tasksRecycler);
        View closeIcon = dialogView.findViewById(R.id.closeIcon);
        View addTaskButton = dialogView.findViewById(R.id.addTaskButton);

        // ✅ Store tasksAdapter globally to update inside removeTaskFromFirestore
        tasksAdapter = new TasksAdapter(classItem.getTasks(), taskToRemove -> {
            removeTaskFromFirestore(taskToRemove, classItem);
        });

        tasksRecycler.setLayoutManager(new LinearLayoutManager(this));
        tasksRecycler.setAdapter(tasksAdapter);

        // Add new task button
        addTaskButton.setOnClickListener(v -> {
            showAddTaskDialog(classItem, tasksAdapter);
        });

        builder.setView(dialogView);
        AlertDialog dialog = builder.create();
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        // Close icon
        closeIcon.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }




    private void removeTaskFromFirestore(UniTask task, ClassWithTasks classItem) {
        String docId = task.getTaskId();
        if (docId == null || docId.isEmpty()) {
            Toast.makeText(this, "Can't remove task without doc ID", Toast.LENGTH_SHORT).show();
            return;
        }

        db.collection("tasks")
                .document(docId)
                .delete()
                .addOnSuccessListener(aVoid -> {
                    Toast.makeText(this, "Task removed", Toast.LENGTH_SHORT).show();

                    // ✅ Remove from local list
                    classItem.getTasks().remove(task);

                    // ✅ Ensure UI updates instantly inside dialog
                    runOnUiThread(() -> {
                        if (tasksAdapter != null) {
                            tasksAdapter.removeTask(task);
                            tasksAdapter.notifyDataSetChanged();
                        }
                    });

                    // ✅ Refresh calendar to reflect changes
                    updateCalendarAppearance();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Error removing task", e);
                    Toast.makeText(this, "Failed to remove task", Toast.LENGTH_SHORT).show();
                });
    }











    /**
     * Show a dialog that lets the user add a new task for this class/time.
     */
    private void showAddTaskDialog(ClassWithTasks classItem, TasksAdapter adapter) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_add_task, null);

        EditText taskTitle = dialogView.findViewById(R.id.taskTitle);
        EditText taskDetails = dialogView.findViewById(R.id.taskDetails);

        builder.setView(dialogView)
                .setPositiveButton("Add Task", (dialog, which) -> {
                    UniTask newTask = new UniTask(
                            taskTitle.getText().toString(),
                            taskDetails.getText().toString(),
                            classItem.getTimeSlot(),
                            selectedBatch,
                            selectedSection
                    );

                    newTask.setDate(stripTime(currentSelectedDate));

                    // First, add the task to Firestore
                    db.collection("tasks")
                            .add(newTask)  // Firestore generates a document ID
                            .addOnSuccessListener(docRef -> {
                                // Get the Firestore document ID and update the taskId field
                                newTask.setTaskId(docRef.getId());

                                // Update Firestore with the correct taskId
                                db.collection("tasks")
                                        .document(newTask.getTaskId())
                                        .update("taskId", newTask.getTaskId())  // Update Firestore with taskId
                                        .addOnSuccessListener(aVoid -> Log.d(TAG, "Task ID updated in Firestore"))
                                        .addOnFailureListener(e -> Log.e(TAG, "Failed to update taskId", e));

                                // Attach to class in memory
                                classItem.addTask(newTask);

                                // Refresh adapter UI
                                adapter.notifyDataSetChanged();

                                updateCalendarAppearance();
                            })
                            .addOnFailureListener(e -> {
                                Log.e(TAG, "Error adding task", e);
                                Toast.makeText(this, "Failed to add task", Toast.LENGTH_SHORT).show();
                            });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }



    private void saveTaskToFirestore(UniTask task) {
        db.collection("tasks")
                .add(task)
                .addOnSuccessListener(docRef -> {
                    String newId = docRef.getId();
                    task.setTaskId(newId);
                    showToast("Task added");
                    // Re-attach the new task locally
                    attachTaskToClass(task);
                    updateCalendarAppearance();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Error adding task", e);
                    showToast("Failed to add task");
                });
    }

    private void showToast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    /**
     * Returns day of week name for a Date, e.g. "sunday", "monday", ...
     */
    private String getDayName(Date date) {
        return new SimpleDateFormat("EEEE", Locale.ENGLISH).format(date);
    }

    /**
     * Safely get a string from a map
     */
    private String safeGetString(Map<String, Object> map, String key) {
        if (map.containsKey(key)) {
            Object val = map.get(key);
            if (val instanceof String) {
                return (String) val;
            }
        }
        return "";
    }
}
