package ed.unicoach.coaching

import ed.unicoach.chat.ToolRegistry
import ed.unicoach.coaching.admissions.CollegeAdmissionsChatTool
import ed.unicoach.coaching.admissions.CollegeAdmissionsService
import ed.unicoach.coaching.aid.FafsaDeadlineChatTool
import ed.unicoach.coaching.aid.FafsaDeadlineService
import ed.unicoach.coaching.aid.FafsaProcessChatTool
import ed.unicoach.coaching.aid.FederalAidPolicyChatTool
import ed.unicoach.coaching.aid.FederalAidPolicyService
import ed.unicoach.coaching.collegelist.CollegeListChatTool
import ed.unicoach.coaching.collegelist.CollegeListService
import ed.unicoach.coaching.costs.CollegeCostChatTool
import ed.unicoach.coaching.costs.CollegeCostService
import ed.unicoach.coaching.family.InviteSupporterChatTool
import ed.unicoach.coaching.moneyprofile.MoneyProfileService
import ed.unicoach.coaching.report.CostReportShareService
import ed.unicoach.coaching.report.RevokeCostReportShareChatTool
import ed.unicoach.coaching.report.ShareCostReportChatTool
import ed.unicoach.coaching.report.StopCostReportOffersChatTool
import ed.unicoach.coaching.testing.AdmissionTestTimelineChatTool
import ed.unicoach.coaching.testing.AutomaticAwardChatTool
import ed.unicoach.coaching.testing.AutomaticAwardService
import ed.unicoach.coaching.testing.NationalMeritChatTool
import ed.unicoach.coaching.testing.NationalMeritService
import ed.unicoach.coaching.testing.TestScoreService
import ed.unicoach.coaching.testing.TestTimelineService
import ed.unicoach.coaching.testing.UpdateTestScoresChatTool
import ed.unicoach.college.Codebook
import ed.unicoach.college.CollegeSearchService
import ed.unicoach.college.CollegeSearchTool
import ed.unicoach.college.FindCollegeTool
import ed.unicoach.college.SimilarCollegesTool
import ed.unicoach.db.Database
import ed.unicoach.family.SupporterInviteService

/**
 * The student coach's registry (RFC 262): the ONE list of the tools advertised
 * on every student turn, mirroring [ed.unicoach.family.SupporterToolRegistry]
 * for the parent's coach.
 *
 * It was built inline in `rest-server`'s composition root, and the web chat
 * calls the coach in-process from `public-web` (brief 0013 D13) — so a second
 * copy there would have been a second list, and the first tool added to one of
 * them would have given the two doors different coaches. Both roots call
 * [standard]; no tool list exists anywhere else.
 */
object StudentToolRegistry {
  /**
   * The production registry, in the order the wire advertises it.
   *
   * The parameters are the collaborators that carry configuration: the invite
   * service (its config and token generator, RFC 232 — the same instance a
   * root's other invite doors mint through) and the share service (its secret,
   * which may be unset, RFC 155). Every other tool reads a stateless service
   * over [database], built here; a root's other routes keep their own
   * instances of those, which is safe because none holds state.
   *
   * The published codebook is read HERE, once, at wiring time (RFC 147): the
   * search tools advertise its region words and render results in its
   * vocabulary, and nothing else in either root reads it. Suspending because it
   * is a database read; a root calls this once, at boot.
   *
   * Several tools share one instance of a service on purpose — the search doors
   * and the money-profile tool read the SAME money-profile service, so the
   * residency a search ranks on is the one the family stated (RFC 169, RFC 245).
   */
  suspend fun standard(
    database: Database,
    supporterInvites: SupporterInviteService,
    costReportShares: CostReportShareService,
  ): ToolRegistry {
    val codebook = Codebook.loadOrEmpty(database)
    val collegeSearch = CollegeSearchService(database)
    val moneyProfiles = MoneyProfileService(database)
    val subjectVocabulary = SubjectVocabularyService(database)
    val intendedSubjects = IntendedSubjectsService(database, subjectVocabulary)
    val sharingPermissions = SharingPermissionService(database)
    return ToolRegistry(
      listOf(
        // Student-scoped since RFC 169, and doubly so since RFC 245: the same
        // money-profile and intended-subjects services the two tools below
        // write through, so a search never reads a second copy of either.
        CollegeChatTool(
          CollegeSearchTool(collegeSearch, codebook),
          moneyProfiles::getForStudent,
          intendedSubjects::getForStudent,
        ),
        // The name door beside the filter door (RFC 154), over the same search
        // service, so a school the student NAMED resolves over the path the iOS
        // picker uses.
        FindCollegeChatTool(FindCollegeTool(collegeSearch)),
        // "Schools like Bowdoin" (RFC 153): the same service and codebook as the
        // search tool, so the three doors speak one vocabulary.
        SimilarCollegesChatTool(
          SimilarCollegesTool(collegeSearch, codebook),
          moneyProfiles::getForStudent,
          intendedSubjects::getForStudent,
        ),
        MoneyProfileChatTool(moneyProfiles),
        CollegeCostChatTool(CollegeCostService(database)),
        CollegeListChatTool(CollegeListService(database)),
        CollegeAdmissionsChatTool(CollegeAdmissionsService(database)),
        // Federal Pell/loan policy parameters (RFC 159).
        FederalAidPolicyChatTool(FederalAidPolicyService(database)),
        // The process half of the same subject (RFC 216): a curated corpus, so
        // it reads nothing.
        FafsaProcessChatTool(),
        // The DATE half (RFC 237): the family's state comes from their money
        // profile, never from the model.
        FafsaDeadlineChatTool(FafsaDeadlineService(database), moneyProfiles),
        // The share door reads the sharing permission and subjects answer (RFC
        // 251), so the reach sentence it hands over is true for this student.
        ShareCostReportChatTool(costReportShares, sharingPermissions::read, intendedSubjects::getForStudent),
        RevokeCostReportShareChatTool(costReportShares),
        // "Never suggest sharing again" (RFC 160): writes an opted_out event, so
        // it stands even when the two doors above decline for want of a secret.
        StopCostReportOffersChatTool(costReportShares),
        // "Connect my mum properly" (RFC 240): the chat door over RFC 232's
        // invite service, so a link the coach hands over is the same kind of
        // credential as one the app hands over.
        InviteSupporterChatTool(supporterInvites),
        // "When do I need to take the SATs" (RFC 197).
        AdmissionTestTimelineChatTool(TestTimelineService(database)),
        // "Is my score worth money at Alabama?" (RFC 239).
        AutomaticAwardChatTool(AutomaticAwardService(database)),
        // "What is the PSAT actually worth?" (RFC 243): whose award a row is
        // comes from the family's own profile, never from the model.
        NationalMeritChatTool(NationalMeritService(database), moneyProfiles),
        // "I got 1340 in March" (RFC 207).
        UpdateTestScoresChatTool(TestScoreService(database)),
        // What the student wants to STUDY (RFC 205).
        IntendedSubjectsChatTool(intendedSubjects, subjectVocabulary),
        // Whether people the student shares with may see their subjects (RFC 251).
        UpdateSharingPermissionChatTool(sharingPermissions),
      ),
    )
  }
}
