package panoptes.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import panoptes.web.JobService;

/**
 * The standard MVC Web Controller responsible for serving the Panoptes HTML user interface.
 * <p>
 * This controller routes standard HTTP GET requests to the corresponding Thymeleaf templates. 
 * It manages the entry point (index), the real-time processing view (status page), and the 
 * final report viewing interface. For the results page, it loads the generated Markdown 
 * report and metadata directly from the local filesystem via the {@link panoptes.web.JobService}.
 * </p>
 */
@Controller
public class PageController {

    private final JobService jobService;

    public PageController(JobService jobService) {
        this.jobService = jobService;
    }

    @GetMapping("/")
    public String index() {
        return "index";
    }

    @GetMapping("/status/{jobId}")
    public String statusPage(@PathVariable String jobId, Model model) {
        model.addAttribute("jobId", jobId);
        return "status"; 
    }

    @GetMapping("/results/{resultId}")
    public String resultsPage(@PathVariable String resultId, Model model) {
        String markdown = jobService.getResult(resultId);
        String title = jobService.getTitle(resultId); 
        
        model.addAttribute("resultId", resultId);
        model.addAttribute("reportTitle", title); 
        model.addAttribute("markdownData", markdown != null ? markdown : "## Error\nData lost or invalid ID.");
        
        return "results"; 
    }
}