/*
 * Copyright 2018 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package controllers

import java.time.LocalDate

import common.Dates
import common.Dates._
import connectors.CalculatorConnector
import controllers.predicates.ValidActiveSession
import controllers.utils.RecoverableFuture
import it.innove.play.pdf.PdfGenerator
import models.resident.TaxYearModel
import play.api.Play.current
import play.api.i18n.Messages
import play.api.i18n.Messages.Implicits._
import play.api.mvc.RequestHeader
import views.html.calculation.{report => views}

import scala.concurrent.Future
import uk.gov.hmrc.http.HeaderCarrier

object ReportController extends ReportController {
  val calcConnector = CalculatorConnector
}

trait ReportController extends ValidActiveSession {

  val calcConnector: CalculatorConnector

  val pdfGenerator = new PdfGenerator

  def host(implicit request: RequestHeader): String = {
    s"http://${request.host}/"
  }

  def getTaxYear(disposalDate: LocalDate)(implicit hc: HeaderCarrier): Future[Option[TaxYearModel]] =
    calcConnector.getTaxYear(disposalDate.format(requestFormatter))

  def getMaxAEA(taxYear: Int)(implicit hc: HeaderCarrier): Future[Option[BigDecimal]] = {
    calcConnector.getFullAEA(taxYear)
  }

  def taxYearStringToInteger (taxYear: String): Future[Int] = {
    Future.successful((taxYear.take(2) + taxYear.takeRight(2)).toInt)
  }

  //###### Gain Summary Report ########\\
  val gainSummaryReport = ValidateSession.async { implicit request =>
    (for {
      answers <- calcConnector.getShareGainAnswers
      costs <- calcConnector.getSharesTotalCosts(answers)
      taxYear <- getTaxYear(answers.disposalDate)
      grossGain <- calcConnector.calculateRttShareGrossGain(answers)
    } yield {pdfGenerator.ok(views.gainSummaryReport(answers, grossGain, taxYear.get, costs), host).asScala()
      .withHeaders("Content-Disposition" -> s"""attachment; filename="${Messages("calc.resident.summary.title")}.pdf"""")}).recoverToStart(homeLink, sessionTimeoutUrl)
  }

  //#####Deductions summary actions#####\\
  val deductionsReport = ValidateSession.async { implicit request =>
    (for {
      answers <- calcConnector.getShareGainAnswers
      costs <- calcConnector.getSharesTotalCosts(answers)
      taxYear <- getTaxYear(answers.disposalDate)
      taxYearInt <- taxYearStringToInteger(taxYear.get.calculationTaxYear)
      maxAEA <- getMaxAEA(taxYearInt)(hc)
      deductionAnswers <- calcConnector.getShareDeductionAnswers
      grossGain <- calcConnector.calculateRttShareGrossGain(answers)
      chargeableGain <- calcConnector.calculateRttShareChargeableGain(answers, deductionAnswers, maxAEA.get)
    } yield
      {pdfGenerator.ok(views.deductionsSummaryReport(answers, deductionAnswers, chargeableGain.get, taxYear.get, costs), host).asScala()
      .withHeaders("Content-Disposition" -> s"""attachment; filename="${Messages("calc.resident.summary.title")}.pdf"""")}).recoverToStart(homeLink, sessionTimeoutUrl)

  }

  //#####Final summary actions#####\\

  val finalSummaryReport = ValidateSession.async { implicit request =>
    (for {
      answers <- calcConnector.getShareGainAnswers
      totalCosts <- calcConnector.getSharesTotalCosts(answers)
      taxYear <- getTaxYear(answers.disposalDate)
      taxYearInt <- taxYearStringToInteger(taxYear.get.calculationTaxYear)
      maxAEA <- getMaxAEA(taxYearInt)(hc)
      grossGain <- calcConnector.calculateRttShareGrossGain(answers)
      deductionAnswers <- calcConnector.getShareDeductionAnswers
      chargeableGain <- calcConnector.calculateRttShareChargeableGain(answers, deductionAnswers, maxAEA.get)
      incomeAnswers <- calcConnector.getShareIncomeAnswers
      currentTaxYear <- Dates.getCurrentTaxYear
      totalGain <- calcConnector.calculateRttShareTotalGainAndTax(answers, deductionAnswers, maxAEA.get, incomeAnswers)
    } yield {
      pdfGenerator.ok(views.finalSummaryReport(answers,
        deductionAnswers,
        incomeAnswers,
        totalGain.get,
        taxYear.get,
        taxYear.get.taxYearSupplied == currentTaxYear,
        totalCosts,
        chargeableGain.get.deductions
      ),
        host).asScala().withHeaders("Content-Disposition" -> s"""attachment; filename="${Messages("calc.resident.summary.title")}.pdf"""")
    }).recoverToStart(homeLink, sessionTimeoutUrl)
  }
}
