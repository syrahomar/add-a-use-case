// UC-STU-deactivate-student: the Deactivate control and its confirmation on the Students page. The search and the
// deactivate calls are stubbed, so these tests check what the page offers, warns about, and sends; the backend's
// own tests (StudentDeactivationIntegrationTest) cover who may deactivate and what is stored.
// Run against the dev stack (backend + `npm run dev`) with `npm run cy:open`.

const john = {
  id: 4,
  firstName: 'John',
  lastName: 'Smith',
  email: 'j.smith@abc.edu',
  teamName: 'Team1',
  enabled: true
}
const eric = {
  id: 5,
  firstName: 'Eric',
  lastName: 'Hudson',
  email: 'e.hudson@abc.edu',
  teamName: 'Team1',
  enabled: false
}

const page = (students) => ({
  flag: true,
  code: 200,
  message: 'Find students successfully',
  data: { content: students, number: 0, size: 50, totalElements: students.length }
})

describe('Deactivate a student', () => {
  beforeEach(() => {
    cy.login('b.gates@abc.edu', '123456')
    cy.intercept('POST', '**/students/search*', { body: page([john, eric]) }).as('search')
    cy.visit('/students')
    cy.wait('@search')
  })

  it('offers Deactivate only for an active student (3a1)', () => {
    cy.contains('tr', 'John').find('[data-cy=deactivate-student]').should('exist')
    cy.contains('tr', 'Eric').find('[data-cy=deactivate-student]').should('not.exist')
  })

  it('warns about the consequences and sends nothing when canceled (steps 3-4, 5a)', () => {
    cy.intercept('PATCH', '**/students/*/deactivate', cy.spy().as('deactivate'))

    cy.contains('tr', 'John').find('[data-cy=deactivate-student]').click()
    cy.get('.el-message-box')
      .should('contain', 'Deactivate John Smith?')
      .and('contain', 'lose access immediately')
      .and('contain', 'This is not a deletion.')
    cy.get('.el-message-box').contains('button', 'Cancel').click()

    cy.get('@deactivate').should('not.have.been.called')
  })

  it('deactivates on confirm, alerts, and reloads the row (steps 5-6)', () => {
    cy.intercept('PATCH', '**/students/4/deactivate', {
      body: {
        flag: true,
        code: 200,
        message: 'Deactivate student successfully',
        data: { ...john, enabled: false }
      }
    }).as('deactivate')

    cy.contains('tr', 'John').find('[data-cy=deactivate-student]').click()
    cy.intercept('POST', '**/students/search*', {
      body: page([{ ...john, enabled: false }, eric])
    }).as('reload')
    cy.get('.el-message-box').contains('button', 'Deactivate').click()

    cy.wait('@deactivate')
    cy.contains('John Smith has been deactivated.')
    cy.wait('@reload')
    cy.contains('tr', 'John')
      .should('contain', 'Disabled')
      .find('[data-cy=deactivate-student]')
      .should('not.exist')
  })

  it('shows the server message when the student was already deactivated (3a2)', () => {
    cy.intercept('PATCH', '**/students/4/deactivate', {
      statusCode: 409,
      body: { flag: false, code: 409, message: 'The student is already deactivated.' }
    }).as('deactivate')

    cy.contains('tr', 'John').find('[data-cy=deactivate-student]').click()
    cy.get('.el-message-box').contains('button', 'Deactivate').click()

    cy.wait('@deactivate')
    cy.contains('The student is already deactivated.')
    cy.wait('@search') // The page reloads its rows to correct the stale status
  })
})
